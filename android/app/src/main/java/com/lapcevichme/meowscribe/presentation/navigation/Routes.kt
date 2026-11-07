package com.lapcevichme.meowscribe.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class ProcessingRoute(val sessionId: Long)

@Serializable
data class TranscriptRoute(val sessionId: Long)

@Serializable
data object RecordRoute

@Serializable
data object SettingsRoute
