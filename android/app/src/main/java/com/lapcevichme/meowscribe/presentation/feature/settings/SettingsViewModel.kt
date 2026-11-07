package com.lapcevichme.meowscribe.presentation.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Profile
import com.lapcevichme.meowscribe.domain.repository.SettingsRepository
import com.lapcevichme.meowscribe.domain.repository.VoiceProfileRepository
import com.lapcevichme.meowscribe.domain.voice.VoiceProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val voices: VoiceProfileRepository,
) : ViewModel() {

    val config: StateFlow<PipelineConfig> = settings.config

    val voiceProfiles: StateFlow<List<VoiceProfile>> = voices.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteVoice(id: Long) {
        viewModelScope.launch { voices.delete(id) }
    }

    fun setProfile(value: Profile) = settings.update { it.copy(profile = value) }
    fun setLanguage(value: String) = settings.update { it.copy(language = value) }
    fun setSpeakers(value: Int?) = settings.update { it.copy(numSpeakers = value) }
    fun setLlm(value: Boolean) = settings.update { it.copy(useLlm = value) }
}
