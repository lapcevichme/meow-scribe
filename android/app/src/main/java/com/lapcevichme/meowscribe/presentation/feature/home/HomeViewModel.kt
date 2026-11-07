package com.lapcevichme.meowscribe.presentation.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.meowscribe.domain.model.ProcessingProgress
import com.lapcevichme.meowscribe.domain.model.Session
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.repository.AudioSource
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.usecase.ImportAudioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HomeFilter { ALL, DONE, IN_PROGRESS }

data class SessionItem(val session: Session, val progress: ProcessingProgress?)

data class HomeUiState(
    val isLoading: Boolean = true,
    val items: List<SessionItem> = emptyList(),
    val filter: HomeFilter = HomeFilter.ALL,
    val totalCount: Int = 0,
    val processingCount: Int = 0,
    val queuedCount: Int = 0,
    val isImporting: Boolean = false,
)

sealed interface HomeEvent {
    data class Message(val text: String) : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val queue: ProcessingQueue,
    private val importAudio: ImportAudioUseCase,
    private val audioSource: AudioSource,
) : ViewModel() {

    private val filter = MutableStateFlow(HomeFilter.ALL)
    private val importing = MutableStateFlow(false)

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<HomeUiState> = combine(
        repository.observeSessions(),
        queue.progress,
        filter,
        importing,
    ) { sessions, progress, f, isImporting ->
        HomeUiState(
            isLoading = false,
            items = sessions.filter { it.matches(f) }.map { SessionItem(it, progress[it.id]) },
            filter = f,
            totalCount = sessions.size,
            processingCount = sessions.count { it.status == SessionStatus.PROCESSING },
            queuedCount = sessions.count { it.status == SessionStatus.QUEUED },
            isImporting = isImporting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setFilter(value: HomeFilter) = filter.update { value }

    fun import(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing.value = true
            val result = importAudio(uris)
            importing.value = false
            val text = buildString {
                append("В очереди: ${result.imported.size}")
                if (result.failed > 0) append(" · не удалось открыть: ${result.failed}")
            }
            _events.send(HomeEvent.Message(text))
        }
    }

    fun onRecordClicked() {
        viewModelScope.launch { _events.send(HomeEvent.Message("Запись с микрофона появится в следующей версии")) }
    }

    fun retry(id: Long) = queue.enqueue(id)

    fun delete(id: Long) {
        viewModelScope.launch {
            queue.cancel(id)
            repository.getSession(id)?.let { audioSource.deleteLocal(it.audioUri) }
            repository.delete(id)
        }
    }

    private fun Session.matches(f: HomeFilter) = when (f) {
        HomeFilter.ALL -> true
        HomeFilter.DONE -> status == SessionStatus.DONE
        HomeFilter.IN_PROGRESS -> status == SessionStatus.PROCESSING || status == SessionStatus.QUEUED
    }
}
