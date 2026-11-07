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
import com.lapcevichme.meowscribe.domain.model.Session
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.usecase.ExportTranscriptUseCase
import com.lapcevichme.meowscribe.domain.usecase.SplitUtteranceUseCase
import com.lapcevichme.meowscribe.presentation.components.speakerIndex
import com.lapcevichme.meowscribe.presentation.navigation.TranscriptRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    ) : TranscriptUiState
}

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

sealed interface TranscriptEvent {
    data class Message(val text: String) : TranscriptEvent
    data class Share(val uri: String) : TranscriptEvent
}

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val repository: SessionRepository,
    private val export: ExportTranscriptUseCase,
    private val splitUtterance: SplitUtteranceUseCase,
) : ViewModel() {

    val sessionId: Long = savedStateHandle.toRoute<TranscriptRoute>().sessionId

    val state: StateFlow<TranscriptUiState> = combine(
        repository.observeSession(sessionId),
        repository.observeTranscript(sessionId),
        repository.observeSpeakerNames(sessionId),
    ) { session, utterances, names ->
        when {
            session == null -> TranscriptUiState.NotFound
            session.status != SessionStatus.DONE -> TranscriptUiState.NotReady(session.status)
            else -> TranscriptUiState.Content(
                session = session,
                utterances = utterances,
                speakerIds = utterances.map { it.speakerId }.distinct().sortedBy { speakerIndex(it) },
                speakerNames = names,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptUiState.Loading)

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

    fun renameSpeaker(speakerId: String, name: String?) {
        viewModelScope.launch { repository.renameSpeaker(sessionId, speakerId, name) }
    }

    fun editUtterance(index: Int, text: String, speakerId: String) {
        viewModelScope.launch { repository.updateUtterance(sessionId, index, text.trim(), speakerId) }
    }

    fun split(index: Int, text: String, cursor: Int, speakerId: String) {
        viewModelScope.launch {
            val ok = splitUtterance(sessionId, index, text.trim(), cursor, speakerId)
            _events.send(TranscriptEvent.Message(if (ok) "Реплика разделена" else "Поставьте курсор внутрь текста"))
        }
    }

    fun exportTo(uri: String) = launchExport("Сохранено") { export.toUri(sessionId, uri) }

    fun exportToDownloads() = launchExport(null) {
        val path = export.toDownloads(sessionId)
        _events.send(TranscriptEvent.Message(path?.let { "Сохранено: $it" } ?: "Не удалось сохранить"))
    }

    fun share() = launchExport(null) { _events.send(TranscriptEvent.Share(export.shareUri(sessionId))) }

    private fun launchExport(successMessage: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { successMessage?.let { _events.send(TranscriptEvent.Message(it)) } }
                .onFailure { _events.send(TranscriptEvent.Message("Ошибка экспорта: ${it.message}")) }
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        exo.release()
    }
}
