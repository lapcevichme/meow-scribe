package com.lapcevichme.meowscribe.data.settings

import android.content.Context
import androidx.core.content.edit
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Profile
import com.lapcevichme.meowscribe.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import javax.inject.Inject
import javax.inject.Singleton

/** Пять значений, DataStore тут избыточен: хватает SharedPreferences. */
@Singleton
class PrefsSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    override val config: StateFlow<PipelineConfig> = _config.asStateFlow()

    override fun update(transform: (PipelineConfig) -> PipelineConfig) {
        val next = _config.updateAndGet(transform)
        prefs.edit {
            putString(KEY_PROFILE, next.profile.name)
            putString(KEY_LANGUAGE, next.language)
            putInt(KEY_SPEAKERS, next.numSpeakers ?: 0)
            putBoolean(KEY_LLM, next.useLlm)
        }
    }

    private val _wallpaperColors = MutableStateFlow(prefs.getBoolean(KEY_WALLPAPER, false))
    override val wallpaperColors: StateFlow<Boolean> = _wallpaperColors.asStateFlow()

    override fun setWallpaperColors(enabled: Boolean) {
        _wallpaperColors.value = enabled
        prefs.edit { putBoolean(KEY_WALLPAPER, enabled) }
    }

    private fun read(): PipelineConfig {
        val defaults = PipelineConfig()
        return PipelineConfig(
            profile = prefs.getString(KEY_PROFILE, null)
                ?.let { runCatching { Profile.valueOf(it) }.getOrNull() } ?: defaults.profile,
            language = prefs.getString(KEY_LANGUAGE, null) ?: defaults.language,
            numSpeakers = prefs.getInt(KEY_SPEAKERS, 0).takeIf { it > 0 },
            useLlm = prefs.getBoolean(KEY_LLM, defaults.useLlm),
        )
    }

    private companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_LANGUAGE = "language"
        const val KEY_SPEAKERS = "num_speakers"
        const val KEY_LLM = "use_llm"
        const val KEY_WALLPAPER = "wallpaper_colors"
    }
}
