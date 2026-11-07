package com.lapcevichme.meowscribe.data.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import com.lapcevichme.meowscribe.domain.pipeline.TranscriptionPipeline
import com.lapcevichme.meowscribe.domain.repository.AudioRecorder
import com.lapcevichme.meowscribe.domain.repository.RecordedAudio
import com.lapcevichme.meowscribe.domain.repository.RecorderState
import com.lapcevichme.meowscribe.domain.repository.RecorderStatus
import com.lapcevichme.meowscribe.service.RecordingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Пишет PCM16 16 кГц моно в WAV. Заголовок дописывается при остановке.
 * Живёт в singleton-scope, поэтому запись переживает поворот экрана и уход с экрана записи.
 */
@Singleton
class WavAudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioRecorder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RecorderState())
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var file: File? = null
    private var out: RandomAccessFile? = null
    @Volatile private var samplesWritten = 0L
    @Volatile private var paused = false
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // разрешение проверяет экран записи
    override fun start() {
        if (_state.value.status != RecorderStatus.IDLE) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuf > 0) { "Микрофон не поддерживает 16 кГц" }
        val bufSize = maxOf(minBuf, CHUNK * 2 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("Не удалось открыть микрофон")
        }
        val dir = File(context.filesDir, "audio").apply { mkdirs() }
        val f = File(dir, "rec-${UUID.randomUUID()}.wav")
        val raf = RandomAccessFile(f, "rw").apply { setLength(0); write(ByteArray(WAV_HEADER)) }

        record = rec
        file = f
        out = raf
        samplesWritten = 0
        paused = false
        running = true
        _state.value = RecorderState(RecorderStatus.RECORDING)
        rec.startRecording()
        RecordingService.start(context)
        job = scope.launch { loop(rec, raf) }
    }

    private fun loop(rec: AudioRecord, raf: RandomAccessFile) {
        val shorts = ShortArray(CHUNK)
        val bytes = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            val n = rec.read(shorts, 0, CHUNK)
            if (n <= 0) {
                if (n < 0) break else continue
            }
            if (paused) continue
            bytes.clear()
            var sum = 0.0
            for (i in 0 until n) {
                bytes.putShort(shorts[i])
                val v = shorts[i] / 32768.0
                sum += v * v
            }
            raf.write(bytes.array(), 0, n * 2)
            samplesWritten += n
            val level = levelOf(sqrt(sum / n))
            _state.update {
                it.copy(
                    elapsedMs = samplesWritten * 1000 / SAMPLE_RATE,
                    levels = (it.levels + level).takeLast(MAX_LEVELS),
                )
            }
        }
    }

    /** dBFS → 0..1: −60 дБ и тише — ноль. */
    private fun levelOf(rms: Double): Float {
        if (rms <= 0) return 0f
        val db = 20 * log10(rms)
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    override fun pause() {
        if (_state.value.status != RecorderStatus.RECORDING) return
        paused = true
        _state.update { it.copy(status = RecorderStatus.PAUSED) }
    }

    override fun resume() {
        if (_state.value.status != RecorderStatus.PAUSED) return
        paused = false
        _state.update { it.copy(status = RecorderStatus.RECORDING) }
    }

    override suspend fun stop(): RecordedAudio? = withContext(Dispatchers.IO) {
        val f = file ?: return@withContext null
        finish()
        val samples = samplesWritten
        if (samples < SAMPLE_RATE / 2) { // меньше полсекунды — нечего распознавать
            f.delete()
            return@withContext null
        }
        RandomAccessFile(f, "rw").use { writeHeader(it, samples) }
        RecordedAudio(Uri.fromFile(f).toString(), samples * 1000 / SAMPLE_RATE)
    }

    override suspend fun discard() = withContext(Dispatchers.IO) {
        val f = file
        finish()
        f?.delete()
        Unit
    }

    private suspend fun finish() {
        running = false
        val j = job
        job = null
        record?.runCatching { stop() }
        j?.join()
        record?.release()
        record = null
        out?.close()
        out = null
        file = null
        _state.value = RecorderState()
        RecordingService.stop(context)
    }

    private fun writeHeader(raf: RandomAccessFile, samples: Long) {
        val dataBytes = samples * 2
        val h = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
    }

    private companion object {
        const val SAMPLE_RATE = TranscriptionPipeline.SAMPLE_RATE
        const val CHUNK = 800 // 50 мс
        const val WAV_HEADER = 44
        const val MAX_LEVELS = 120
    }
}
