package com.lapcevichme.meowscribe.data.pipeline.sherpa

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.lapcevichme.meowscribe.data.models.ModelLocator
import com.lapcevichme.meowscribe.data.models.ModelLocator.Paths
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Profile
import com.lapcevichme.meowscribe.domain.pipeline.PipelineEvent
import com.lapcevichme.meowscribe.domain.pipeline.Stage
import com.lapcevichme.meowscribe.domain.pipeline.Stats
import com.lapcevichme.meowscribe.domain.pipeline.TranscriptionPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настоящий пайплайн на sherpa-onnx, полностью на устройстве.
 *
 * VAD (Silero) → [LID whisper-tiny, если language=auto] → диаризация (pyannote-seg-3.0 + эмбеддинги,
 * быстрая кластеризация) → ASR по речевым чанкам VAD (GigaAM v3 RNNT / Parakeet) → склейка слов со спикерами.
 *
 * Модели живут только внутри своей стадии и освобождаются сразу после неё — пиковая RAM ≈ самая
 * тяжёлая модель плюс PCM. Эталонная реализация и подбор параметров — bench/pipeline.py.
 */
@Singleton
class SherpaPipeline @Inject constructor(
    locator: ModelLocator,
) : TranscriptionPipeline {

    private val assets: AssetManager = locator.assets
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    override fun run(pcm: FloatArray, config: PipelineConfig): Flow<PipelineEvent> = channelFlow {
        val started = SystemClock.elapsedRealtime()
        val audioMs = pcm.size * 1000L / SR
        val stageTimes = EnumMap<Stage, Long>(Stage::class.java)
        val tuning = Tuning.forProfile(config.profile)
        var peakRam = 0L

        suspend fun <T> stage(stage: Stage, block: suspend () -> T): T {
            val t0 = SystemClock.elapsedRealtime()
            send(PipelineEvent.Progress(stage, 0f))
            val result = block()
            stageTimes[stage] = SystemClock.elapsedRealtime() - t0
            peakRam = maxOf(peakRam, rssMb())
            send(PipelineEvent.Progress(stage, 1f))
            return result
        }

        try {
            if (pcm.size < SR / 4) {
                send(done(emptyList(), audioMs, started, peakRam, stageTimes))
                return@channelFlow
            }

            val chunks = stage(Stage.VAD) { vadChunks(pcm, tuning) }
            ensureActive()
            if (chunks.isEmpty()) {
                send(done(emptyList(), audioMs, started, peakRam, stageTimes))
                return@channelFlow
            }

            val language = when (config.language) {
                "ru", "en" -> config.language
                else -> {
                    send(PipelineEvent.Progress(Stage.VAD, 1f, "Определяем язык"))
                    detectLanguage(pcm, chunks)
                }
            }
            Log.i(TAG, "audio ${audioMs}ms, ${chunks.size} chunks, lang=$language, threads=$threads")

            val segments = stage(Stage.DIARIZATION) { diarize(pcm, config.numSpeakers, tuning) }
            ensureActive()

            val words = stage(Stage.ASR) { transcribe(pcm, chunks, language, segments, tuning) }
            ensureActive()

            val utterances = stage(Stage.MERGE) {
                WordMerger.buildUtterances(words, tuning.splitGap, tuning.sentenceGap)
            }
            send(done(utterances, audioMs, started, peakRam, stageTimes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pipeline failed", e)
            send(PipelineEvent.Failed(e))
        }
    }.buffer(64).flowOn(Dispatchers.Default)

    // ------------------------------------------------------------------ stages

    private fun vadChunks(pcm: FloatArray, t: Tuning): List<IntRange> {
        val cfg = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = Paths.VAD,
                threshold = 0.5f,
                minSilenceDuration = t.vadMinSilence,
                minSpeechDuration = 0.1f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = t.vadMaxSpeech,
            ),
            sampleRate = SR,
            numThreads = 1,
        )
        val vad = Vad(assets, cfg)
        val out = ArrayList<IntRange>()
        try {
            fun drain() {
                while (!vad.empty()) {
                    val seg = vad.front()
                    out += seg.start.toInt() until (seg.start.toInt() + seg.samples.size)
                    vad.pop()
                }
            }
            var i = 0
            val window = FloatArray(VAD_WINDOW)
            while (i + VAD_WINDOW <= pcm.size) {
                System.arraycopy(pcm, i, window, 0, VAD_WINDOW)
                vad.acceptWaveform(window)
                drain()
                i += VAD_WINDOW
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        val pad = (t.pad * SR).toInt()
        return out.map { maxOf(0, it.first - pad) until minOf(pcm.size, it.last + 1 + pad) }
    }

    private fun detectLanguage(pcm: FloatArray, chunks: List<IntRange>): String {
        val lid = SpokenLanguageIdentification(
            assets,
            SpokenLanguageIdentificationConfig(
                whisper = SpokenLanguageIdentificationWhisperConfig(
                    encoder = Paths.LID_ENCODER,
                    decoder = Paths.LID_DECODER,
                ),
                numThreads = threads,
            ),
        )
        try {
            // до 29 с чистой речи: whisper принимает строго меньше 30 с
            val limit = 29 * SR
            val speech = FloatArray(minOf(limit, chunks.sumOf { it.last - it.first + 1 }))
            var pos = 0
            for (c in chunks) {
                val n = minOf(c.last - c.first + 1, speech.size - pos)
                if (n <= 0) break
                System.arraycopy(pcm, c.first, speech, pos, n)
                pos += n
            }
            val stream = lid.createStream()
            try {
                stream.acceptWaveform(speech, SR)
                val lang = lid.compute(stream)
                Log.i(TAG, "LID: $lang")
                return if (lang == "en") "en" else "ru"
            } finally {
                stream.release()
            }
        } finally {
            lid.release()
        }
    }

    private suspend fun ProducerScope<PipelineEvent>.diarize(
        pcm: FloatArray,
        numSpeakers: Int?,
        t: Tuning,
    ): List<SpeakerSegment> {
        val sd = OfflineSpeakerDiarization(
            assets,
            OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(Paths.DIARIZATION_SEGMENTATION, t.windowShift),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = Paths.DIARIZATION_EMBEDDING, numThreads = threads),
                clustering = FastClusteringConfig(
                    numClusters = numSpeakers?.takeIf { it > 0 } ?: -1,
                    threshold = t.clusterThreshold,
                ),
                minDurationOn = t.minDurationOn,
                minDurationOff = t.minDurationOff,
            ),
        )
        try {
            val scope = this
            val callback = DiarizationCallback { done, total ->
                if (total > 0) scope.trySend(PipelineEvent.Progress(Stage.DIARIZATION, done.toFloat() / total))
                if (scope.isActive) 0 else 1
            }
            val segs = sd.processWithCallback(pcm, callback)
            return segs.map { SpeakerSegment(it.start, it.end, it.speaker) }.sortedBy { it.start }
        } finally {
            sd.release()
        }
    }

    private suspend fun ProducerScope<PipelineEvent>.transcribe(
        pcm: FloatArray,
        chunks: List<IntRange>,
        language: String,
        segments: List<SpeakerSegment>,
        t: Tuning,
    ): List<TimedWord> {
        val recognizer = createRecognizer(language)
        val words = ArrayList<TimedWord>()
        try {
            chunks.forEachIndexed { i, range ->
                ensureActive()
                val samples = pcm.copyOfRange(range.first, range.last + 1)
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SR)
                    recognizer.decode(stream)
                    val r = recognizer.getResult(stream)
                    val chunkWords = WordMerger.tokensToWords(
                        r.tokens, r.timestamps,
                        offset = range.first.toFloat() / SR,
                        chunkEnd = (range.last + 1).toFloat() / SR,
                    )
                    words += WordMerger.assignSpeakers(chunkWords, segments)
                } finally {
                    stream.release()
                }
                send(PipelineEvent.Progress(Stage.ASR, (i + 1f) / chunks.size, "Фрагмент ${i + 1}/${chunks.size}"))
                send(PipelineEvent.Partial(WordMerger.buildUtterances(words, t.splitGap, t.sentenceGap)))
            }
        } finally {
            recognizer.release()
        }
        return words // уже со спикерами
    }

    private fun createRecognizer(language: String): OfflineRecognizer {
        val transducer = if (language == "en") {
            OfflineTransducerModelConfig(
                encoder = Paths.ASR_EN_ENCODER,
                decoder = Paths.ASR_EN_DECODER,
                joiner = Paths.ASR_EN_JOINER,
            ) to Paths.ASR_EN_TOKENS
        } else {
            OfflineTransducerModelConfig(
                encoder = Paths.ASR_RU_ENCODER,
                decoder = Paths.ASR_RU_DECODER,
                joiner = Paths.ASR_RU_JOINER,
            ) to Paths.ASR_RU_TOKENS
        }
        return OfflineRecognizer(
            assets,
            OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = transducer.first,
                    tokens = transducer.second,
                    modelType = "nemo_transducer",
                    numThreads = threads,
                ),
                decodingMethod = "greedy_search",
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun done(
        utterances: List<com.lapcevichme.meowscribe.domain.model.Utterance>,
        audioMs: Long,
        started: Long,
        peakRam: Long,
        stageTimes: Map<Stage, Long>,
    ): PipelineEvent.Done {
        val stats = Stats(
            audioMs = audioMs,
            processingMs = SystemClock.elapsedRealtime() - started,
            peakRamMb = peakRam.takeIf { it > 0 },
            stageTimesMs = stageTimes.toMap(),
        )
        Log.i(TAG, "done: ${utterances.size} utt, rtf=${"%.3f".format(stats.rtf)}, peakRss=${stats.peakRamMb}MB, stages=${stats.stageTimesMs}")
        return PipelineEvent.Done(utterances, stats)
    }

    private fun rssMb(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024)
        }
    }.getOrNull() ?: 0L

    /**
     * JNI sherpa-onnx ищет у колбэка метод ровно с сигнатурой `invoke(IIJ)Ljava/lang/Integer;`.
     * Лямбды Kotlin 2.x компилируются через invokedynamic и такого метода не имеют → нативный abort.
     * У обычного класса override `invoke` компилируется именно в эту сигнатуру.
     */
    private class DiarizationCallback(
        private val onProgress: (done: Int, total: Int) -> Int,
    ) : (Int, Int, Long) -> Int {
        override fun invoke(done: Int, total: Int, arg: Long): Int = onProgress(done, total)
    }

    /** Параметры подобраны на стенде (bench/sweep_diar.py, bench/README.md). */
    internal data class Tuning(
        val clusterThreshold: Float = 0.8f,
        val minDurationOn: Float = 0.3f,
        val minDurationOff: Float = 0.5f,
        /** Шаг окна pyannote (доля 10-с окна). Больше шаг — меньше эмбеддингов, быстрее, чуть хуже DER. */
        val windowShift: Float,
        val vadMinSilence: Float = 0.3f,
        val vadMaxSpeech: Float = 20f,
        val pad: Float = 0.15f,
        val splitGap: Float = 3f,
        val sentenceGap: Float = 0.4f,
    ) {
        companion object {
            // стенд (ru): FAST — DER 8.9%, RTF ×0.66; ACCURATE — DER 6.9%. WER одинаковый.
            fun forProfile(profile: Profile) = when (profile) {
                Profile.FAST -> Tuning(windowShift = 0.25f)
                Profile.ACCURATE -> Tuning(windowShift = 0.1f)
            }
        }
    }

    private companion object {
        const val TAG = "SherpaPipeline"
        const val SR = TranscriptionPipeline.SAMPLE_RATE
        const val VAD_WINDOW = 512
    }
}
