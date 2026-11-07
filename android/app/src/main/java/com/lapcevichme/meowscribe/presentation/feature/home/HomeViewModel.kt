package com.lapcevichme.meowscribe.presentation.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.meowscribe.domain.model.ProcessingProgress
import com.lapcevichme.meowscribe.domain.model.Session
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.processing.SummaryState
import com.lapcevichme.meowscribe.domain.processing.summaryState
import com.lapcevichme.meowscribe.domain.repository.AudioSource
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.usecase.ImportAudioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lapcevichme.meowscribe.presentation.components.summaryTitle
import javax.inject.Inject

enum class HomeFilter { ALL, DONE, IN_PROGRESS }

data class SessionItem(
    val session: Session,
    val progress: ProcessingProgress?,
    /** Реплика, в которой нашёлся поисковый запрос. */
    val snippet: String? = null,
    /** Сводка LLM ждёт очереди или считается. */
    val summary: SummaryState? = null,
    /** Отмену нажали, обработка ещё останавливается. */
    val cancelling: Boolean = false,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val items: List<SessionItem> = emptyList(),
    val filter: HomeFilter = HomeFilter.ALL,
    val totalCount: Int = 0,
    val processingCount: Int = 0,
    val queuedCount: Int = 0,
    val isImporting: Boolean = false,
    val query: String = "",
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
    private val query = MutableStateFlow("")

    private data class Search(val query: String, val matches: Map<Long, String>)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val search = query.flatMapLatest { q ->
        repository.searchTranscripts(q).map { Search(q.trim(), it) }
    }

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<HomeUiState> = combine(
        repository.observeSessions(),
        combine(queue.progress, queue.summaries, queue.cancelling, ::Triple),
        filter,
        importing,
        search,
    ) { sessions, (progress, summaries, cancelling), f, isImporting, s ->
        val found = if (s.query.isEmpty()) sessions else sessions.filter {
            it.id in s.matches || it.title.contains(s.query, ignoreCase = true) ||
                it.summaryTitle?.contains(s.query, ignoreCase = true) == true
        }
        HomeUiState(
            isLoading = false,
            items = found.filter { it.matches(f) }.map {
                SessionItem(it, progress[it.id], s.matches[it.id], summaryState(it.id, summaries, progress), it.id in cancelling)
            },
            query = s.query,
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

    fun setQuery(value: String) = query.update { value }

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
