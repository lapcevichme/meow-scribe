package com.lapcevichme.meowscribe

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.lapcevichme.meowscribe.data.models.ModelLocator
import com.lapcevichme.meowscribe.data.models.ModelLocator.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Проверка, что нативные библиотеки sherpa-onnx грузятся на этой архитектуре и модели читаются из assets.
 * Печатает время загрузки, RTF и RSS процесса — так сравниваем x86_64-эмулятор жюри и Pixel 8.
 *
 *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lapcevichme.meowscribe.ModelsSmokeTest
 *   adb logcat -s MeowSmoke
 */
@RunWith(AndroidJUnit4::class)
class ModelsSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val locator = ModelLocator(instrumentation.targetContext)
    private val speech: FloatArray by lazy { readWav16kMono("speech_ru.wav") }
    private val audioSec get() = speech.size / 16000f

    @Test
    fun inventory() {
        report("ABI ${android.os.Build.SUPPORTED_ABIS.first()}, cores ${Runtime.getRuntime().availableProcessors()}")
        locator.inventory().forEach { report("${it.name}: ${it.path} ${it.sizeBytes?.let { s -> "${s / 1_048_576} МБ" } ?: "НЕТ"}") }
    }

    @Test
    fun vad() {
        assumeTrue("нет VAD", locator.has(Paths.VAD))
        val (vad, loadMs) = timed {
            Vad(
                locator.assets,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(model = Paths.VAD),
                    sampleRate = 16000,
                    numThreads = 1,
                ),
            )
        }
        val (segments, runMs) = timed {
            var count = 0
            var i = 0
            while (i < speech.size) {
                vad.acceptWaveform(speech.copyOfRange(i, minOf(i + 512, speech.size)))
                i += 512
                while (!vad.empty()) { count++; vad.pop() }
            }
            vad.flush()
            while (!vad.empty()) { count++; vad.pop() }
            count
        }
        vad.release()
        report("VAD: load ${loadMs} мс, run ${runMs} мс, RTF ${rtf(runMs)}, сегментов $segments, RSS ${rssMb()} МБ")
        assertTrue(segments > 0)
    }

    @Test
    fun asr() {
        assumeTrue("нет ASR", locator.has(Paths.ASR_RU_ENCODER))
        val rssBefore = rssMb()
        val (recognizer, loadMs) = timed {
            OfflineRecognizer(
                locator.assets,
                OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = Paths.ASR_RU_ENCODER,
                            decoder = Paths.ASR_RU_DECODER,
                            joiner = Paths.ASR_RU_JOINER,
                        ),
                        tokens = Paths.ASR_RU_TOKENS,
                        modelType = "nemo_transducer",
                        numThreads = threads(),
                        provider = "cpu",
                    ),
                ),
            )
        }
        val rssLoaded = rssMb()
        val (text, runMs) = timed {
            val stream = recognizer.createStream()
            stream.acceptWaveform(speech, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            stream.release()
            result.text
        }
        recognizer.release()
        report("ASR: load ${loadMs} мс, run ${runMs} мс на ${"%.1f".format(audioSec)} с, RTF ${rtf(runMs)}, RSS $rssBefore → $rssLoaded МБ")
        report("ASR текст: «$text»")
        assertTrue(text.isNotBlank())
    }

    @Test
    fun diarization() {
        assumeTrue("нет диаризации", locator.has(Paths.DIARIZATION_SEGMENTATION) && locator.has(Paths.DIARIZATION_EMBEDDING))
        val (sd, loadMs) = timed {
            OfflineSpeakerDiarization(
                locator.assets,
                OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = Paths.DIARIZATION_SEGMENTATION),
                        numThreads = threads(),
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(model = Paths.DIARIZATION_EMBEDDING, numThreads = threads()),
                    clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                ),
            )
        }
        val (segments, runMs) = timed { sd.process(speech) }
        sd.release()
        report(
            "Диаризация: load ${loadMs} мс, run ${runMs} мс, RTF ${rtf(runMs)}, " +
                "сегментов ${segments.size}, спикеров ${segments.map { it.speaker }.distinct().size}, RSS ${rssMb()} МБ",
        )
        assertTrue(segments.isNotEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private fun rtf(runMs: Long) = "%.3f".format(runMs / 1000f / audioSec)

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = SystemClock.elapsedRealtime()
        val value = block()
        return value to SystemClock.elapsedRealtime() - start
    }

    private fun rssMb(): Long = File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() / 1024
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $line\n") })
    }

    /** Простой WAV PCM16 16 кГц моно — ровно то, что лежит в test_wavs у моделей sherpa-onnx. */
    private fun readWav16kMono(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open(name).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var sampleRate = 0
        var channels = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    channels = buf.getShort(pos + 10).toInt()
                    sampleRate = buf.getInt(pos + 12)
                }
                "data" -> {
                    check(sampleRate == 16000 && channels == 1) { "Ожидали 16 кГц моно, а там $sampleRate Гц × $channels" }
                    val n = minOf(size, bytes.size - pos - 8) / 2
                    return FloatArray(n) { i -> buf.getShort(pos + 8 + i * 2) / 32768f }
                }
            }
            pos += 8 + size + (size and 1)
        }
        error("В $name нет data-чанка")
    }

    private companion object {
        const val TAG = "MeowSmoke"
    }
}
