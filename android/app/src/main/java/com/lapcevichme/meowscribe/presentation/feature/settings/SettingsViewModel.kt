package com.lapcevichme.meowscribe.presentation.feature.settings

import androidx.lifecycle.ViewModel
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Profile
import com.lapcevichme.meowscribe.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val config: StateFlow<PipelineConfig> = settings.config

    fun setProfile(value: Profile) = settings.update { it.copy(profile = value) }
    fun setLanguage(value: String) = settings.update { it.copy(language = value) }
    fun setSpeakers(value: Int?) = settings.update { it.copy(numSpeakers = value) }
    fun setLlm(value: Boolean) = settings.update { it.copy(useLlm = value) }
}
