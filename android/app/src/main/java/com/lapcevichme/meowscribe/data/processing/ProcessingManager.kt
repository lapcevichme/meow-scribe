package com.lapcevichme.meowscribe.data.processing

import android.content.Context
import android.os.SystemClock
import com.lapcevichme.meowscribe.data.llm.LlmSummarizer
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.ProcessingProgress
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.pipeline.PipelineEvent
import com.lapcevichme.meowscribe.domain.pipeline.Stage
import com.lapcevichme.meowscribe.domain.pipeline.TranscriptionPipeline
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.repository.AudioSource
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.TranscriptExporter
import com.lapcevichme.meowscribe.domain.usecase.ExportTranscriptUseCase
import com.lapcevichme.meowscribe.domain.usecase.RecognizeVoicesUseCase
import com.lapcevichme.meowscribe.service.ProcessingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Последовательно обрабатывает сессии: декодирование → пайплайн → сохранение → автоэкспорт в Загрузки.
 * Живёт в собственном scope, не зависит от экранов. Пока есть работа, держит Foreground Service.
 */
@Singleton
class ProcessingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SessionRepository,
    private val audioSource: AudioSource,
    private val pipeline: TranscriptionPipeline,
    private val exporter: TranscriptExporter,
    private val recognizeVoices: RecognizeVoicesUseCase,
    private val summarizer: LlmSummarizer,
) : ProcessingQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val queued = Collections.synchronizedSet(LinkedHashSet<Long>())
    private val cancelled = Collections.synchronizedSet(HashSet<Long>())

    /** Защищает связку «очереди пусты → busy = false» от гонки с [enqueue]. */
    private val busyLock = Any()

    @Volatile private var currentId: Long? = null
    @Volatile private var currentJob: Job? = null
    @Volatile private var currentIsSummary = false

    private val _progress = MutableStateFlow<Map<Long, ProcessingProgress>>(emptyMap())
    override val progress: StateFlow<Map<Long, ProcessingProgress>> = _progress.asStateFlow()

    /** Сводки ждут, пока не опустеет очередь расшифровок: LLM не должна задерживать следующие записи. */
    private val _summaries = MutableStateFlow<Set<Long>>(emptySet())
    override val summaries: StateFlow<Set<Long>> = _summaries.asStateFlow()

    private val _cancelling = MutableStateFlow<Set<Long>>(emptySet())
    override val cancelling: StateFlow<Set<Long>> = _cancelling.asStateFlow()

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        scope.launch {
            while (true) {
                // Расшифровки всегда первыми, сводки — только когда очередь расшифровок пуста.
                var id = queue.tryReceive().getOrNull()
                if (id == null) {
                    val summaryId = _summaries.value.firstOrNull()
                    if (summaryId != null) {
                        processSummary(summaryId)
                        continue
                    }
                    synchronized(busyLock) {
                        if (queued.isEmpty() && _summaries.value.isEmpty()) _busy.value = false
                    }
                    id = queue.receive()
                }
                if (id == WAKE) continue // в очередь добавили сводку — цикл заберёт её сам
                queued.remove(id)
                if (cancelled.remove(id)) continue
                process(id)
            }
        }
    }

    override fun enqueue(sessionId: Long) {
        if (currentId == sessionId) {
            if (!currentIsSummary) return
            currentJob?.cancel() // запись пересчитывается заново — её сводка устарела
        }
        synchronized(busyLock) {
            if (!queued.add(sessionId)) return
            _busy.value = true
        }
        cancelled.remove(sessionId)
        _summaries.update { it - sessionId } // запись пересчитывается — старая сводка не нужна
        ProcessingService.start(context)
        queue.trySend(sessionId)
        scope.launch { repository.setStatus(sessionId, SessionStatus.QUEUED) }
    }

    override fun cancel(sessionId: Long) {
        _summaries.update { it - sessionId }
        if (currentId == sessionId) {
            if (!currentIsSummary) _cancelling.update { it + sessionId }
            currentJob?.cancel()
        } else if (queued.remove(sessionId)) {
            cancelled.add(sessionId)
            scope.launch { repository.setStatus(sessionId, SessionStatus.ERROR, CANCELLED_MESSAGE) }
        }
    }

    override fun summarize(sessionId: Long): Boolean {
        if (!summarizer.isAvailable()) return false
        if (sessionId in queued || sessionId in _summaries.value) return true
        synchronized(busyLock) {
            _summaries.update { it + sessionId }
            _busy.value = true
        }
        ProcessingService.start(context)
        queue.trySend(WAKE)
        return true
    }

    override suspend fun resumePending() {
        repository.pendingSessionIds().forEach { enqueue(it) }
    }

    private suspend fun process(id: Long) {
        val session = repository.getSession(id) ?: return
        currentId = id
        val job = scope.launch { runSession(id, session.audioUri, session.config) }
        currentJob = job
        job.join()
        currentJob = null
        currentId = null
        _progress.update { it - id }
        _cancelling.update { it - id }
    }

    private suspend fun runSession(id: Long, audioUri: String, config: PipelineConfig) {
        val started = SystemClock.elapsedRealtime()
        val stageTimes = mutableMapOf<Stage, Long>()
        var stageStarted = started
        var lastStage = Stage.DECODE
        var partial = emptyList<com.lapcevichme.meowscribe.domain.model.Utterance>()
        var audioMs: Long? = null
        var peakRam = 0L

        fun publish(stage: Stage, fraction: Float, message: String?) {
            val now = SystemClock.elapsedRealtime()
            if (stage != lastStage) {
                stageTimes[lastStage] = now - stageStarted
                stageStarted = now
                lastStage = stage
            }
            peakRam = maxOf(peakRam, readRssMb() ?: 0L)
            _progress.update {
                it + (id to ProcessingProgress(
                    sessionId = id,
                    stage = stage,
                    stageFraction = fraction.coerceIn(0f, 1f),
                    overallFraction = overall(stage, fraction),
                    message = message,
                    partial = partial,
                    elapsedMs = now - started,
                    audioMs = audioMs,
                    peakRamMb = peakRam.takeIf { r -> r > 0 },
                    stageTimesMs = stageTimes.toMap(),
                ))
            }
        }

        try {
            repository.setStatus(id, SessionStatus.PROCESSING)
            publish(Stage.DECODE, 0f, null)
            val audio = audioSource.decode(audioUri) { f -> publish(Stage.DECODE, f, "Приводим к 16 кГц моно") }
            audioMs = audio.durationMs
            repository.setDuration(id, audio.durationMs)

            pipeline.run(audio.pcm, config).collect { event ->
                when (event) {
                    is PipelineEvent.Progress -> publish(event.stage, event.fraction, event.message)
                    is PipelineEvent.Partial -> {
                        partial = event.utterances
                        publish(lastStage, _progress.value[id]?.stageFraction ?: 0f, _progress.value[id]?.message)
                    }
                    is PipelineEvent.Done -> {
                        // Сверка с сохранёнными голосами. Пока база пуста, ничего не считается и RTF не страдает.
                        if (recognizeVoices.isNeeded()) {
                            publish(lastStage, 1f, "Сверяем голоса с сохранёнными")
                            try {
                                recognizeVoices(id, audio.pcm, event.utterances)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Не узнали голоса — не повод терять расшифровку.
                            }
                        }
                        val total = SystemClock.elapsedRealtime() - started
                        repository.saveResult(id, event.utterances, total)
                        autoExport(id)
                        // LLM — уже после сохранения и экспорта, в отдельной очереди с низким приоритетом.
                        if (config.useLlm && event.utterances.isNotEmpty() && summarizer.isAvailable()) {
                            _summaries.update { it + id }
                        }
                    }
                    is PipelineEvent.Failed -> throw event.error
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { repository.setStatus(id, SessionStatus.ERROR, CANCELLED_MESSAGE) }
        } catch (e: Throwable) {
            repository.setStatus(id, SessionStatus.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun processSummary(id: Long) {
        currentId = id
        currentIsSummary = true
        val started = SystemClock.elapsedRealtime()
        fun publish(f: Float) {
            _progress.update {
                it + (id to ProcessingProgress(
                    sessionId = id,
                    stage = Stage.LLM,
                    stageFraction = f.coerceIn(0f, 1f),
                    overallFraction = f.coerceIn(0f, 1f),
                    message = "Готовим сводку",
                    partial = emptyList(),
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    audioMs = null,
                    peakRamMb = null,
                    stageTimesMs = emptyMap(),
                ))
            }
        }
        // Сразу помечаем «считается»: загрузка модели занимает несколько секунд до первого прогресса.
        publish(0f)
        val job = scope.launch {
            val utterances = repository.getTranscript(id)
            if (utterances.isNotEmpty()) summarize(id, utterances, ::publish)
        }
        currentJob = job
        job.join() // отмена сводки — не ошибка записи: транскрипт уже сохранён
        currentJob = null
        currentId = null
        currentIsSummary = false
        _progress.update { it - id }
        _summaries.update { it - id }
    }

    private suspend fun summarize(
        id: Long,
        utterances: List<com.lapcevichme.meowscribe.domain.model.Utterance>,
        onProgress: (Float) -> Unit,
    ) {
        try {
            val s = summarizer.summarize(utterances, repository.getSpeakerNames(id), onProgress) ?: return
            val text = buildString {
                append(s.title)
                s.bullets.forEach { append("\n• ").append(it) }
            }
            repository.setSummary(id, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("ProcessingManager", "summary failed", e)
        }
    }

    private suspend fun autoExport(id: Long) {
        runCatching {
            val title = repository.getSession(id)?.title ?: return
            exporter.saveToDownloads(
                ExportTranscriptUseCase.exportFileName(title),
                exporter.toJson(repository.getTranscript(id)),
            )
        }
    }

    private fun overall(stage: Stage, fraction: Float): Float {
        var before = 0f
        for (s in Stage.entries) {
            if (s == stage) break
            before += STAGE_WEIGHTS.getValue(s)
        }
        val total = STAGE_WEIGHTS.values.sum()
        return ((before + STAGE_WEIGHTS.getValue(stage) * fraction.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f)
    }

    /** Текущий RSS процесса в МБ из /proc/self/status. */
    private fun readRssMb(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024)
        }
    }.getOrNull()

    companion object {
        const val CANCELLED_MESSAGE = "Обработка отменена"

        /** Будит цикл очереди, когда работы нет, а в очередь добавили сводку. id сессий начинаются с 1. */
        private const val WAKE = -1L

        private val STAGE_WEIGHTS = mapOf(
            Stage.DECODE to 0.05f,
            Stage.VAD to 0.05f,
            Stage.DIARIZATION to 0.2f,
            Stage.ASR to 0.55f,
            Stage.MERGE to 0.05f,
            Stage.LLM to 0f, // сводка идёт отдельной задачей и в прогресс расшифровки не входит
        )
    }
}
