package com.lapcevichme.meowscribe.presentation.feature.transcript

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import com.lapcevichme.meowscribe.di.ApplicationScope
import com.lapcevichme.meowscribe.domain.export.ExportDocument
import com.lapcevichme.meowscribe.domain.export.ExportFormat
import com.lapcevichme.meowscribe.domain.export.ExportOptions
import com.lapcevichme.meowscribe.domain.model.Session
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.SpeakerEmbedder
import com.lapcevichme.meowscribe.domain.repository.VoiceProfileRepository
import com.lapcevichme.meowscribe.domain.usecase.ExportTranscriptUseCase
import com.lapcevichme.meowscribe.domain.usecase.MergeUtterancesUseCase
import com.lapcevichme.meowscribe.domain.usecase.RememberVoiceUseCase
import com.lapcevichme.meowscribe.domain.usecase.SplitUtteranceUseCase
import com.lapcevichme.meowscribe.domain.voice.VoiceMatch
import com.lapcevichme.meowscribe.presentation.components.speakerIndex
import com.lapcevichme.meowscribe.presentation.navigation.TranscriptRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.lapcevichme.meowscribe.domain.processing.summaryState
import com.lapcevichme.meowscribe.domain.processing.SummaryState
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.usecase.ReprocessSessionUseCase
import javax.inject.Inject

sealed interface TranscriptUiState {
    data object Loading : TranscriptUiState
    data object NotFound : TranscriptUiState
    data class NotReady(val status: SessionStatus) : TranscriptUiState

    data class Content(
        val session: Session,
        val utterances: List<Utterance>,
        /** Все спикеры записи в порядке номеров: speaker_1, speaker_2, … */
        val speakerIds: List<String>,
        val speakerNames: Map<String, String>,
        /** Спикеры, узнанные по сохранённым голосам или привязанные вручную. */
        val voices: Map<String, VoiceMatch> = emptyMap(),
        /** Можно ли запоминать голоса (есть модель эмбеддингов). */
        val canRememberVoice: Boolean = false,
        /** Сводка LLM ещё не готова: ждёт очереди или считается. */
        val summaryState: SummaryState? = null,
    ) : TranscriptUiState
}

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

/** Действие в снекбаре. */
enum class SnackAction(val label: String) {
    UNDO("Отменить"),
    SHARE("Поделиться"),
}

sealed interface TranscriptEvent {
    data class Message(val text: String, val action: SnackAction? = null) : TranscriptEvent
    data class Share(val uri: String, val mimeType: String) : TranscriptEvent
    data class Copy(val text: String) : TranscriptEvent
    /** Запись ушла на повторную обработку — открываем экран прогресса. */
    data class Reprocessing(val sessionId: Long) : TranscriptEvent
}

/** Что показываем в шторке экспорта. */
data class ExportPreview(
    val document: ExportDocument,
    /** Первые строки файла. */
    val head: String,
    val truncated: Boolean,
    val sizeBytes: Int,
)

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val repository: SessionRepository,
    private val export: ExportTranscriptUseCase,
    private val splitUtterance: SplitUtteranceUseCase,
    private val mergeUtterances: MergeUtterancesUseCase,
    private val rememberVoice: RememberVoiceUseCase,
    private val voiceRepository: VoiceProfileRepository,
    private val reprocessSession: ReprocessSessionUseCase,
    embedder: SpeakerEmbedder,
    private val queue: ProcessingQueue,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val canRememberVoice = embedder.isAvailable

    val sessionId: Long = savedStateHandle.toRoute<TranscriptRoute>().sessionId

    val state: StateFlow<TranscriptUiState> = combine(
        repository.observeSession(sessionId),
        repository.observeTranscript(sessionId),
        repository.observeSpeakerNames(sessionId),
        voiceRepository.observeMatches(sessionId),
        combine(queue.summaries, queue.progress) { s, p -> summaryState(sessionId, s, p) }.distinctUntilChanged(),
    ) { session, utterances, names, voices, summary ->
        when {
            session == null -> TranscriptUiState.NotFound
            session.status != SessionStatus.DONE -> TranscriptUiState.NotReady(session.status)
            else -> TranscriptUiState.Content(
                session = session,
                utterances = utterances,
                speakerIds = utterances.map { it.speakerId }.distinct().sortedBy { speakerIndex(it) },
                speakerNames = names,
                voices = voices,
                canRememberVoice = canRememberVoice,
                summaryState = summary,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptUiState.Loading)

    private val _exportOptions = MutableStateFlow(ExportOptions())
    val exportOptions: StateFlow<ExportOptions> = _exportOptions.asStateFlow()

    /** Предпросмотр считается, только пока шторка экспорта открыта и подписана. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val exportPreview: StateFlow<ExportPreview?> = combine(state, _exportOptions) { s, o -> s to o }
        .mapLatest { (s, options) ->
            val content = s as? TranscriptUiState.Content ?: return@mapLatest null
            val doc = export.render(content.session, content.utterances, content.speakerNames, options)
            val lines = doc.content.lineSequence().take(PREVIEW_LINES + 1).toList()
            ExportPreview(
                document = doc,
                head = lines.take(PREVIEW_LINES).joinToString("\n").trimEnd(),
                truncated = lines.size > PREVIEW_LINES,
                sizeBytes = doc.content.toByteArray(Charsets.UTF_8).size,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    /** Последний сохранённый файл — для «Поделиться» из снекбара. */
    private var lastExport: ExportDocument? = null

    /** Расшифровка до последнего разделения или склейки — для «Отменить». */
    private var undoSnapshot: List<Utterance>? = null

    /** Спикер, чей голос сейчас запоминается (декодирование + эмбеддинг, несколько секунд). */
    private val _voiceBusy = MutableStateFlow<String?>(null)
    val voiceBusy: StateFlow<String?> = _voiceBusy.asStateFlow()

    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    private val _events = Channel<TranscriptEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val exo: ExoPlayer = ExoPlayer.Builder(context).build()
    private var ticker: Job? = null
    private var mediaSet = false

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _player.update { it.copy(isPlaying = isPlaying, positionMs = exo.currentPosition) }
                if (isPlaying) startTicker() else ticker?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _player.update { it.copy(durationMs = exo.duration.coerceAtLeast(0)) }
                }
            }
        })
        viewModelScope.launch {
            state.collect { s ->
                if (s is TranscriptUiState.Content && !mediaSet) {
                    mediaSet = true
                    exo.setMediaItem(MediaItem.fromUri(s.session.audioUri))
                    exo.prepare()
                    _player.update { it.copy(durationMs = s.session.durationMs ?: 0) }
                }
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                _player.update { it.copy(positionMs = exo.currentPosition) }
                delay(40)
            }
        }
    }

    fun togglePlay() {
        if (exo.isPlaying) {
            exo.pause()
        } else {
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
        }
    }

    fun seekTo(positionMs: Long, play: Boolean = false) {
        exo.seekTo(positionMs.coerceAtLeast(0))
        _player.update { it.copy(positionMs = positionMs) }
        if (play && !exo.isPlaying) exo.play()
    }

    fun cycleSpeed() {
        val next = when (_player.value.speed) {
            1f -> 1.5f
            1.5f -> 2f
            2f -> 0.75f
            else -> 1f
        }
        exo.playbackParameters = PlaybackParameters(next)
        _player.update { it.copy(speed = next) }
    }

    fun renameSpeaker(speakerId: String, name: String?, remember: Boolean) {
        val clean = name?.trim().orEmpty()
        viewModelScope.launch {
            val linked = (state.value as? TranscriptUiState.Content)?.voices?.get(speakerId)
            if (clean.isEmpty()) {
                repository.renameSpeaker(sessionId, speakerId, null)
                voiceRepository.unlink(sessionId, speakerId)
                return@launch
            }
            if (!remember) {
                repository.renameSpeaker(sessionId, speakerId, clean)
                // Переименовали узнанного спикера в кого-то другого — значит, узнали неверно.
                if (linked != null && !linked.name.equals(clean, ignoreCase = true)) voiceRepository.unlink(sessionId, speakerId)
                return@launch
            }
            // Имя — сразу. Голос считается в скоупе приложения: на длинной записи это декодирование всего файла
            // (десятки секунд), и раньше уход с экрана молча отменял запоминание вместе с именем.
            repository.renameSpeaker(sessionId, speakerId, clean)
            _voiceBusy.value = speakerId
            val job = appScope.async { rememberVoice(sessionId, speakerId, clean) }
            val message = try {
                when (val r = job.await()) {
                    is RememberVoiceUseCase.Result.Created -> "Голос «${r.name}» запомнен"
                    is RememberVoiceUseCase.Result.Updated -> "Голос «${r.name}» уточнён: записей ${r.samples}"
                    RememberVoiceUseCase.Result.TooLittleSpeech -> "Имя сохранено, но речи слишком мало, чтобы запомнить голос"
                    RememberVoiceUseCase.Result.NoModel -> "Модель голосов не найдена"
                }
            } catch (e: CancellationException) {
                throw e // ушли с экрана: job в скоупе приложения досчитает сам
            } catch (e: Exception) {
                "Не удалось запомнить голос: ${e.message}"
            } finally {
                _voiceBusy.value = null
            }
            _events.send(TranscriptEvent.Message(message))
        }
    }

    fun editUtterance(index: Int, text: String, speakerId: String) {
        viewModelScope.launch { repository.updateUtterance(sessionId, index, text.trim(), speakerId) }
    }

    fun split(index: Int, text: String, cursor: Int, speakerId: String) {
        viewModelScope.launch {
            val before = repository.getTranscript(sessionId)
            val ok = splitUtterance(sessionId, index, text.trim(), cursor, speakerId)
            if (ok) undoSnapshot = before
            _events.send(
                if (ok) TranscriptEvent.Message("Реплика разделена", SnackAction.UNDO)
                else TranscriptEvent.Message("Поставьте курсор внутрь текста"),
            )
        }
    }

    fun merge(index: Int, withNext: Boolean, text: String, speakerId: String) {
        viewModelScope.launch {
            val before = repository.getTranscript(sessionId)
            if (mergeUtterances(sessionId, index, withNext, text, speakerId)) {
                undoSnapshot = before
                _events.send(TranscriptEvent.Message("Реплики склеены", SnackAction.UNDO))
            }
        }
    }

    fun reprocess(config: PipelineConfig) {
        viewModelScope.launch {
            exo.pause()
            if (reprocessSession(sessionId, config)) _events.send(TranscriptEvent.Reprocessing(sessionId))
            else _events.send(TranscriptEvent.Message("Запись уже обрабатывается"))
        }
    }

    /** Сводка по текущему тексту. LLM детерминирована: без правок текста результат будет тем же. */
    fun refreshSummary() {
        viewModelScope.launch {
            if (!queue.summarize(sessionId)) _events.send(TranscriptEvent.Message("Языковой модели нет в этой сборке"))
        }
    }

    fun onSnackAction(action: SnackAction) {
        when (action) {
            SnackAction.UNDO -> undo()
            SnackAction.SHARE -> lastExport?.let { doc -> launchExport { shareDocument(doc) } }
        }
    }

    private fun undo() {
        val snapshot = undoSnapshot ?: return
        undoSnapshot = null
        viewModelScope.launch {
            repository.replaceTranscript(sessionId, snapshot)
            _events.send(TranscriptEvent.Message("Отменено"))
        }
    }

    // ------------------------------------------------------------ экспорт

    fun setExportOptions(options: ExportOptions) {
        _exportOptions.value = options
    }

    /** Имя файла для «Сохранить как…» по текущим настройкам. */
    fun exportFileName(): String? = (state.value as? TranscriptUiState.Content)?.let {
        ExportTranscriptUseCase.exportFileName(it.session.title, _exportOptions.value.format)
    }

    /** Кнопка «JSON» в шапке: один тап — файл по схеме в Загрузках. */
    fun quickJson() = saveToDownloads(ExportOptions(format = ExportFormat.JSON))

    fun saveToDownloads(options: ExportOptions = _exportOptions.value) = launchExport {
        val doc = export.render(sessionId, options)
        val path = export.toDownloads(doc)
        if (path != null) {
            lastExport = doc
            _events.send(TranscriptEvent.Message("Сохранено в $path", SnackAction.SHARE))
        } else {
            _events.send(TranscriptEvent.Message("Не удалось сохранить файл"))
        }
    }

    fun exportTo(uri: String) = launchExport {
        export.toUri(export.render(sessionId, _exportOptions.value), uri)
        _events.send(TranscriptEvent.Message("Файл сохранён"))
    }

    fun share() = launchExport { shareDocument(export.render(sessionId, _exportOptions.value)) }

    fun copy() = launchExport {
        val doc = export.render(sessionId, _exportOptions.value)
        _events.send(TranscriptEvent.Copy(doc.content))
    }

    private suspend fun shareDocument(doc: ExportDocument) {
        // SRT как text/plain: application/x-subrip понимают немногие приложения в списке «Поделиться».
        val mime = if (doc.mimeType == ExportFormat.SRT.mimeType) "text/plain" else doc.mimeType
        _events.send(TranscriptEvent.Share(export.shareUri(doc), mime))
    }

    private fun launchExport(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(TranscriptEvent.Message("Ошибка экспорта: ${e.message}"))
            }
        }
    }

    private companion object {
        const val PREVIEW_LINES = 14
    }

    override fun onCleared() {
        ticker?.cancel()
        exo.release()
    }
}
