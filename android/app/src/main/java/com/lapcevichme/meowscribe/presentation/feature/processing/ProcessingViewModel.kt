package com.lapcevichme.meowscribe.presentation.feature.processing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lapcevichme.meowscribe.domain.model.ProcessingProgress
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.presentation.navigation.ProcessingRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface ProcessingUiState {
    data object Loading : ProcessingUiState
    data object NotFound : ProcessingUiState

    data class Running(
        val title: String,
        val durationMs: Long?,
        val queued: Boolean,
        val progress: ProcessingProgress?,
    ) : ProcessingUiState

    data class Done(
        val title: String,
        val speakerCount: Int,
        val processingMs: Long?,
        val rtf: Double?,
    ) : ProcessingUiState

    data class Failed(val title: String, val message: String) : ProcessingUiState
}

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: SessionRepository,
    private val queue: ProcessingQueue,
) : ViewModel() {

    val sessionId: Long = savedStateHandle.toRoute<ProcessingRoute>().sessionId

    val state: StateFlow<ProcessingUiState> = combine(
        repository.observeSession(sessionId),
        queue.progress.map { it[sessionId] },
    ) { session, progress ->
        when {
            session == null -> ProcessingUiState.NotFound
            session.status == SessionStatus.DONE -> ProcessingUiState.Done(
                title = session.title,
                speakerCount = session.speakerCount,
                processingMs = session.processingMs,
                rtf = session.rtf,
            )
            session.status == SessionStatus.ERROR -> ProcessingUiState.Failed(
                session.title,
                session.errorMessage ?: "Ошибка обработки",
            )
            else -> ProcessingUiState.Running(
                title = session.title,
                durationMs = session.durationMs,
                queued = session.status == SessionStatus.QUEUED && progress == null,
                progress = progress,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingUiState.Loading)

    fun cancel() = queue.cancel(sessionId)
    fun retry() = queue.enqueue(sessionId)
}
