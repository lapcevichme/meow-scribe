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
            val (mono, sampleRate) = decodeToMono(Uri.parse(localUri), onProgress)
            val t1 = SystemClock.elapsedRealtime()
            val pcm = Resampler.resample(mono, sampleRate, TranscriptionPipeline.SAMPLE_RATE)
            Log.i(TAG, "decode ${t1 - t0} мс, resample $sampleRate→16000 ${SystemClock.elapsedRealtime() - t1} мс, аудио ${pcm.size / 16} мс")
            DecodedAudio(pcm, pcm.size * 1000L / TranscriptionPipeline.SAMPLE_RATE)
        }

    private suspend fun decodeToMono(uri: Uri, onProgress: (Float) -> Unit): Pair<FloatArray, Int> {
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

            val out = FloatBuilder(if (durationUs > 0) (durationUs / 1_000_000.0 * sampleRate).toInt() + 4096 else 1 shl 20)
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
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            val n = fb.remaining()
                            if (floatScratch.size < n) floatScratch = FloatArray(n)
                            fb.get(floatScratch, 0, n)
                            out.addInterleaved(floatScratch, n, channels, 1f)
                        } else {
                            val sb = buf.asShortBuffer()
                            val n = sb.remaining()
                            if (shortScratch.size < n) shortScratch = ShortArray(n)
                            sb.get(shortScratch, 0, n)
                            out.addInterleaved(shortScratch, n, channels)
                        }
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
            return out.toArray() to sampleRate
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

    /** Растущий FloatArray без боксинга. */
    private class FloatBuilder(initial: Int) {
        private var data = FloatArray(initial.coerceAtLeast(1024))
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
        }

        /** Сводит interleaved PCM16 к моно. */
        fun addInterleaved(src: ShortArray, n: Int, channels: Int) {
            val frames = n / channels
            ensure(frames)
            val scale = 1f / (32768f * channels)
            var k = 0
            for (fr in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += src[k++]
                data[size++] = sum * scale
            }
        }

        /** Сводит interleaved float PCM к моно. */
        fun addInterleaved(src: FloatArray, n: Int, channels: Int, gain: Float) {
            val frames = n / channels
            ensure(frames)
            val scale = gain / channels
            var k = 0
            for (fr in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += src[k++]
                data[size++] = sum * scale
            }
        }
    }
}
