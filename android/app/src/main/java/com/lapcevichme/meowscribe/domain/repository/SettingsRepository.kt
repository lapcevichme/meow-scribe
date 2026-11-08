package com.lapcevichme.meowscribe.domain.repository

import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import kotlinx.coroutines.flow.StateFlow

/** Настройки обработки по умолчанию: применяются к новым записям. */
interface SettingsRepository {
    val config: StateFlow<PipelineConfig>
    fun update(transform: (PipelineConfig) -> PipelineConfig)

    /** Цвета из обоев (Material You) вместо фирменной палитры. По умолчанию выключено. */
    val wallpaperColors: StateFlow<Boolean>
    fun setWallpaperColors(enabled: Boolean)
}
