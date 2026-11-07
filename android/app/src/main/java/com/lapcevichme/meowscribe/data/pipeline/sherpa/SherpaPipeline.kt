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
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
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

            val utterances = stage(Stage.MERGE) { merge(pcm, words, segments, tuning) }
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
        val maxSamples = (t.vadMaxSpeech * SR).toInt()
        return out
            .map { maxOf(0, it.first - pad) until minOf(pcm.size, it.last + 1 + pad) }
            .flatMap { ChunkSplitter.split(pcm, it, maxSamples, SR) }
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
            fun identify(samples: FloatArray): String {
                val stream = lid.createStream()
                try {
                    stream.acceptWaveform(samples, SR)
                    return lid.compute(stream)
                } finally {
                    stream.release()
                }
            }
            val first = identify(speech)
            Log.i(TAG, "LID: $first")
            if (first != "en") return "ru"
            // Русский в приоритете, а ошибка «ru → en» губит весь транскрипт: на реверберации
            // whisper-tiny иногда слышит в русском английский. Подтверждаем «en» голосованием
            // по 5 VAD-чанкам (≥ 2 с), равномерно взятым по записи: нужно большинство «en».
            val long = chunks.filter { it.last - it.first + 1 >= 2 * SR }
            if (long.size < 3) return "en"
            val pick = if (long.size >= 5) List(5) { k -> long[Math.round(k * (long.size - 1) / 4f)] } else long
            val votes = pick.count { identify(pcm.copyOfRange(it.first, it.last + 1)) == "en" }
            Log.i(TAG, "LID confirm: $votes/${pick.size} en")
            return if (votes * 2 > pick.size) "en" else "ru"
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
                .map { SpeakerSegment(it.start, it.end, it.speaker) }
                .sortedBy { it.start }
            return if (numSpeakers != null && numSpeakers > 0) segs else absorbSmallSpeakers(pcm, segs, t)
        } finally {
            sd.release()
        }
    }

    /** Мелкие «спикеры» (несколько секунд речи) → к самому похожему, см. [Reclusterer]. */
    private fun absorbSmallSpeakers(pcm: FloatArray, segs: List<SpeakerSegment>, t: Tuning): List<SpeakerSegment> {
        val durations = segs.groupBy { it.speaker }.mapValues { (_, l) -> l.sumOf { (it.end - it.start).toDouble() }.toFloat() }
        val total = durations.values.sum()
        // нечего склеивать — не грузим модель эмбеддингов
        if (durations.size < 2 || durations.values.none { it < minOf(t.minSpeakerSec, 0.15f * total) }) return segs
        val extractor = createExtractor()
        val embeddings = try {
            speakerEmbeddings(extractor, pcm, segs, durations.keys)
        } finally {
            extractor.release()
        }
        if (embeddings.size < 2) return segs
        val label = Reclusterer.relabel(embeddings, durations, t.minSpeakerSec)
        Log.i(TAG, "recluster: ${durations.size} → ${label.values.toSet().size} спикеров")
        return segs.map { it.copy(speaker = label.getValue(it.speaker)) }
    }

    private fun createExtractor() = SpeakerEmbeddingExtractor(
        assets,
        SpeakerEmbeddingExtractorConfig(model = Paths.DIARIZATION_EMBEDDING, numThreads = threads),
    )

    /** Эмбеддинг каждого спикера по его самым длинным сегментам, до [EMB_MAX_SEC] с — как в bench. */
    private fun speakerEmbeddings(
        extractor: SpeakerEmbeddingExtractor,
        pcm: FloatArray,
        segs: List<SpeakerSegment>,
        speakers: Collection<Int>,
    ): Map<Int, FloatArray> {
        val embeddings = HashMap<Int, FloatArray>()
        for (k in speakers.sorted()) {
            val stream = extractor.createStream()
            try {
                var taken = 0f
                for (s in segs.filter { it.speaker == k }.sortedByDescending { it.end - it.start }) {
                    if (taken >= EMB_MAX_SEC) break
                    val from = (s.start * SR).toInt().coerceIn(0, pcm.size)
                    val to = (s.end * SR).toInt().coerceIn(from, pcm.size)
                    if (to > from) stream.acceptWaveform(pcm.copyOfRange(from, to), SR)
                    taken += s.end - s.start
                }
                stream.inputFinished()
                if (extractor.isReady(stream)) embeddings[k] = Reclusterer.normalize(extractor.compute(stream))
            } finally {
                stream.release()
            }
        }
        return embeddings
    }

    /** Нормированный эмбеддинг куска [start, end) с, null — слишком короткий. */
    private fun embed(extractor: SpeakerEmbeddingExtractor, pcm: FloatArray, start: Float, end: Float): FloatArray? {
        val from = (start * SR).toInt().coerceIn(0, pcm.size)
        val to = (end * SR).toInt().coerceIn(from, pcm.size)
        if (to <= from) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(pcm.copyOfRange(from, to), SR)
            stream.inputFinished()
            if (extractor.isReady(stream)) Reclusterer.normalize(extractor.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    /**
     * Слова со спикерами → реплики: переоценка предложений по голосу ([SentenceRelabeler]), поддакивания
     * отдельными репликами, сглаживание коротких кусков внутри предложения. 1:1 с концом run() в bench.
     */
    private suspend fun ProducerScope<PipelineEvent>.merge(
        pcm: FloatArray,
        words: List<TimedWord>,
        segments: List<SpeakerSegment>,
        t: Tuning,
    ): List<com.lapcevichme.meowscribe.domain.model.Utterance> {
        val relabeled = relabelSentences(pcm, words, segments, t)
        val (main, backchannels) = WordMerger.extractBackchannels(relabeled, t.backchannelMaxSec)
        Log.i(TAG, "merge: поддакиваний отдельно ${backchannels.size}")
        return WordMerger.buildUtterances(WordMerger.smoothSpeakers(main, t.smoothRunSec), t.splitGap, t.sentenceGap, backchannels)
    }

    private suspend fun ProducerScope<PipelineEvent>.relabelSentences(
        pcm: FloatArray,
        words: List<TimedWord>,
        segments: List<SpeakerSegment>,
        t: Tuning,
    ): List<TimedWord> {
        if (t.sentenceMinSec <= 0f) return words
        val durations = segments.groupBy { it.speaker }.mapValues { (_, l) -> l.sumOf { (it.end - it.start).toDouble() }.toFloat() }
        if (durations.size < 2) return words
        val ranges = SentenceRelabeler.candidates(words, t.sentenceMinSec)
        if (ranges.isEmpty()) return words
        val extractor = createExtractor()
        try {
            val units = ranges.mapIndexedNotNull { i, r ->
                ensureActive()
                if (i % 16 == 0) send(PipelineEvent.Progress(Stage.MERGE, i.toFloat() / ranges.size, "Сверяем голоса"))
                embed(extractor, pcm, words[r.first].start, words[r.last].end)?.let { v ->
                    SentenceRelabeler.Unit(r, v, words[r.last].end - words[r.first].start, WordMerger.mainSpeaker(words, r))
                }
            }
            val clustered = SentenceRelabeler.clusterCentroids(
                units, durations, t.sentenceMajorShare, t.sentenceClusterMinSec, t.sentenceClusterMax, t.sentenceClusterBalance,
            )
            val centroids = clustered ?: speakerEmbeddings(extractor, pcm, segments, durations.keys)
            val out = SentenceRelabeler.relabel(
                words, units, centroids, durations, t.sentenceMargin, t.sentenceMajorShare, t.sentenceFragmentSim,
            )
            Log.i(
                TAG,
                "sentences: ${units.size} сверено, центроиды ${if (clustered != null) "по кластерам" else "по сегментам"}, " +
                    "переназначено ${units.count { out[it.range.first].speaker != words[it.range.first].speaker }}",
            )
            return out
        } finally {
            extractor.release()
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
                    words += WordMerger.assignBackchannels(WordMerger.assignSpeakers(chunkWords, segments), segments)
                } finally {
                    stream.release()
                }
                send(PipelineEvent.Progress(Stage.ASR, (i + 1f) / chunks.size, "Фрагмент ${i + 1}/${chunks.size}"))
                send(PipelineEvent.Partial(WordMerger.buildUtterances(WordMerger.smoothSpeakers(words, t.smoothRunSec), t.splitGap, t.sentenceGap)))
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
        /** Спикеры с речью меньше (но не больше 15% всей речи) отдаются самому похожему. Стенд: DER ru 6.8→6.3%, AMI 28.6→27.5%. */
        val minSpeakerSec: Float = 6f,
        /** Шаг окна pyannote (доля 10-с окна). Больше шаг — меньше эмбеддингов, быстрее, чуть хуже DER. */
        val windowShift: Float,
        val vadMinSilence: Float = 0.3f,
        val vadMaxSpeech: Float = 20f,
        val pad: Float = 0.15f,
        val splitGap: Float = 3f,
        val sentenceGap: Float = 0.4f,
        /**
         * Куски другого спикера короче этого внутри предложения отдаются основному, см. WordMerger.smoothSpeakers.
         * 0 — выкл. Стенд AMI: 2 с портило DER (+0.9 п.п.), 1 с почти не мешает и убирает разрывы фраз.
         */
        val smoothRunSec: Float = 1f,
        /** Поддакивания короче этого — отдельными репликами, см. WordMerger.extractBackchannels. 0 — выкл. */
        val backchannelMaxSec: Float = 1.5f,
        /** Предложения от этой длины сверяются по голосу, см. [SentenceRelabeler]. 0 — выкл. */
        val sentenceMinSec: Float = 1f,
        /** Перевес косинуса к другому спикеру, с которого предложение переходит к нему. */
        val sentenceMargin: Float = 0.1f,
        /** Предложения отдаём только спикерам с такой долей речи, а не мелким обрывкам кластеризации. */
        val sentenceMajorShare: Float = 0.1f,
        /** Предложения от этой длины кластеризуются, из кластеров — центроиды спикеров. */
        val sentenceClusterMinSec: Float = 1.5f,
        /** Не больше стольких самых длинных предложений в кластеризации (O(n³), на часовой записи их ~1000). */
        val sentenceClusterMax: Int = 300,
        /**
         * Кластер должен набрать хотя бы такую долю длительности предложений с его меткой диаризации, иначе
         * центроиды по сегментам. Стенд ru_06: кластеризация выделила третьего (0.19) — DER 18 → 45% без проверки.
         */
        val sentenceClusterBalance: Float = 0.5f,
        /**
         * Мелкий спикер (< sentenceMajorShare) без центроида кластера: голос ближе этого к основному — обрывок,
         * его предложения переносим; иначе живой человек, не трогаем. Стенд: 0.5 путает живых (ru_multi), 0.7 — мимо обрывков (AMI).
         */
        val sentenceFragmentSim: Float = 0.6f,
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
        const val EMB_MAX_SEC = 30f
    }
}
