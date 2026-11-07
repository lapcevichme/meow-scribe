package com.lapcevichme.meowscribe.presentation.feature.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.repository.AudioRecorder
import com.lapcevichme.meowscribe.domain.repository.RecorderStatus
import com.lapcevichme.meowscribe.domain.repository.SettingsRepository
import com.lapcevichme.meowscribe.domain.usecase.ImportAudioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class RecordUiState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0,
    val levels: List<Float> = emptyList(),
    val config: PipelineConfig = PipelineConfig(),
    val isSaving: Boolean = false,
)

sealed interface RecordEvent {
    data class Message(val text: String) : RecordEvent
    data class Saved(val sessionId: Long) : RecordEvent
    data object Discarded : RecordEvent
}

@HiltViewModel
class RecordViewModel @Inject constructor(
    private val recorder: AudioRecorder,
    settings: SettingsRepository,
    private val importAudio: ImportAudioUseCase,
) : ViewModel() {

    private val saving = MutableStateFlow(false)

    val state: StateFlow<RecordUiState> = combine(recorder.state, settings.config, saving) { r, config, isSaving ->
        RecordUiState(r.status, r.elapsedMs, r.levels, config, isSaving)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    private val _events = Channel<RecordEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Главная кнопка: старт → пауза → продолжить. */
    fun toggle() {
        when (recorder.state.value.status) {
            RecorderStatus.IDLE -> runCatching { recorder.start() }.onFailure {
                viewModelScope.launch { _events.send(RecordEvent.Message(it.message ?: "Микрофон недоступен")) }
            }
            RecorderStatus.RECORDING -> recorder.pause()
            RecorderStatus.PAUSED -> recorder.resume()
        }
    }

    fun finish() {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            val audio = runCatching { recorder.stop() }.getOrNull()
            if (audio == null) {
                saving.value = false
                _events.send(RecordEvent.Message("Запись слишком короткая"))
                return@launch
            }
            val title = String.format(Locale("ru"), "Запись %1\$te %1\$tb, %1\$tH:%1\$tM", System.currentTimeMillis())
            val id = importAudio.addLocal(title, audio.localUri, audio.durationMs)
            _events.send(RecordEvent.Saved(id))
        }
    }

    fun discard() {
        viewModelScope.launch {
            recorder.discard()
            _events.send(RecordEvent.Discarded)
        }
    }
}
