package com.lapcevichme.meowscribe.domain.model

enum class Profile { FAST, ACCURATE }

data class PipelineConfig(
    val profile: Profile = Profile.FAST,
    val language: String = "ru", // "ru" | "en" | "auto"
    val numSpeakers: Int? = null, // null = определить автоматически
    val useLlm: Boolean = true,
)
