package com.lapcevichme.meowscribe.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.lapcevichme.meowscribe.domain.pipeline.TranscriptionPipeline
import com.lapcevichme.meowscribe.domain.repository.AudioSource
import com.lapcevichme.meowscribe.domain.repository.DecodedAudio
import com.lapcevichme.meowscribe.domain.repository.ImportedAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "MeowAudio"

@Singleton
class MediaCodecAudioSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioSource {

    private val audioDir: File get() = File(context.filesDir, "audio").apply { mkdirs() }

    override suspend fun import(externalUri: String): ImportedAudio = withContext(Dispatchers.IO) {
        val uri = Uri.parse(externalUri)
        val resolver = context.contentResolver
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Запись"
        val ext = displayName.substringAfterLast('.', "").ifEmpty {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "audio"
        }
        val target = File(audioDir, "${UUID.randomUUID()}.$ext")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть файл" }
            target.outputStream().use { input.copyTo(it) }
        }
        val duration = runCatching { probeDurationMs(target) }.getOrNull()
        ImportedAudio(title = displayName, localUri = Uri.fromFile(target).toString(), durationMs = duration)
    }

    override suspend fun deleteLocal(localUri: String) {
        withContext(Dispatchers.IO) {
            Uri.parse(localUri).path?.let { File(it).delete() }
        }
    }

    override suspend fun decode(localUri: String, onProgress: (Float) -> Unit): DecodedAudio =
        withContext(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val (pcm, samples) = decodeTo16k(Uri.parse(localUri), onProgress)
            val durationMs = samples * 1000L / TranscriptionPipeline.SAMPLE_RATE
            Log.i(TAG, "decode+resample ${SystemClock.elapsedRealtime() - t0} мс, аудио $durationMs мс, буфер ${pcm.size * 4L shr 20} МБ")
            DecodedAudio(pcm, durationMs)
        }

    /**
     * Декодирует, сводит в моно и сразу передискретизирует в 16 кГц — кусками, по мере выхода из декодера.
     * Целиком в памяти только результат: полтора часа — 346 МБ вместо ~1 ГБ на исходной частоте плюс копий.
     * @return массив и число реальных отсчётов в нём (хвост массива может быть тишиной, см. [Pcm16kBuilder]).
     */
    private suspend fun decodeTo16k(uri: Uri, onProgress: (Float) -> Unit): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val out = Pcm16kBuilder(durationUs)
            var resampler: StreamingResampler? = null
            var mono = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReported = -1
            var shortScratch = ShortArray(0)
            var floatScratch = FloatArray(0)

            while (!outputDone) {
                coroutineContext.ensureActive()
                // Кормим декодер без ожидания, пока есть свободные входные буферы: блокирующий dequeue
                // на каждом шаге превращал 2.5 мин аудио в ~15 с декодирования.
                while (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    val buf = dec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                when (val outIdx = dec.dequeueOutputBuffer(info, 2_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outIdx >= 0) {
                        val buf = dec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        // Частота известна точно только после INFO_OUTPUT_FORMAT_CHANGED (у HE-AAC она вдвое выше, чем в контейнере).
                        val rs = resampler ?: StreamingResampler(sampleRate, TranscriptionPipeline.SAMPLE_RATE, out::append)
                            .also { resampler = it }
                        val frames = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            val n = fb.remaining()
                            if (floatScratch.size < n) floatScratch = FloatArray(n)
                            fb.get(floatScratch, 0, n)
                            if (mono.size < n / channels) mono = FloatArray(n / channels)
                            downmix(floatScratch, n, channels, mono)
                        } else {
                            val sb = buf.asShortBuffer()
                            val n = sb.remaining()
                            if (shortScratch.size < n) shortScratch = ShortArray(n)
                            sb.get(shortScratch, 0, n)
                            if (mono.size < n / channels) mono = FloatArray(n / channels)
                            downmix(shortScratch, n, channels, mono)
                        }
                        rs.push(mono, frames)
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (outputDone) {
                            onProgress(1f)
                        } else if (durationUs > 0) {
                            // У EOS-буфера presentationTimeUs бывает 0 — поэтому он обработан выше.
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
            }
            resampler?.finish()
            return out.result()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun probeDurationMs(file: File): Long? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            mmr.release()
        }
    }

    /** Сводит interleaved PCM16 к моно. @return число кадров. */
    private fun downmix(src: ShortArray, n: Int, channels: Int, dst: FloatArray): Int {
        val frames = n / channels
        val scale = 1f / (32768f * channels)
        var k = 0
        for (fr in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += src[k++]
            dst[fr] = sum * scale
        }
        return frames
    }

    /** Сводит interleaved float PCM к моно. @return число кадров. */
    private fun downmix(src: FloatArray, n: Int, channels: Int, dst: FloatArray): Int {
        val frames = n / channels
        val scale = 1f / channels
        var k = 0
        for (fr in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += src[k++]
            dst[fr] = sum * scale
        }
        return frames
    }

    /**
     * Буфер результата на 16 кГц. Размер берём из длительности контейнера с запасом в секунду и выделяем
     * один раз: удвоение и финальный copyOf на длинной записи требовали бы ещё столько же памяти.
     */
    private class Pcm16kBuilder(durationUs: Long) {
        private var data = FloatArray(
            if (durationUs > 0) (durationUs * TranscriptionPipeline.SAMPLE_RATE / 1_000_000L).toInt() + SLACK
            else TranscriptionPipeline.SAMPLE_RATE * 60,
        )
        private var size = 0

        fun append(src: FloatArray, n: Int) {
            if (size + n > data.size) {
                // Длительность в контейнере оказалась меньше реальной — растём понемногу, а не вдвое.
                data = data.copyOf(size + n + maxOf(SLACK, data.size / 8))
            }
            System.arraycopy(src, 0, data, size, n)
            size += n
        }

        /** Небольшой хвост из нулей оставляем как есть (это тишина), большой — обрезаем копией. */
        fun result(): Pair<FloatArray, Int> {
            if (data.size - size > SLACK * 2) data = data.copyOf(size)
            return data to size
        }

        private companion object {
            const val SLACK = TranscriptionPipeline.SAMPLE_RATE // 1 с
        }
    }
}
