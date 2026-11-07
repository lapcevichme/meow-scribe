package com.lapcevichme.meowscribe.domain.repository

import kotlinx.coroutines.flow.StateFlow

enum class RecorderStatus { IDLE, RECORDING, PAUSED }

data class RecorderState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0,
    /** Последние уровни громкости 0..1, самый свежий в конце. */
    val levels: List<Float> = emptyList(),
)

data class RecordedAudio(val localUri: String, val durationMs: Long)

/** Запись с микрофона сразу в 16 кГц моно: пайплайну не нужен ресемплинг. */
interface AudioRecorder {
    val state: StateFlow<RecorderState>

    /** Бросает исключение, если микрофон недоступен или нет разрешения. */
    fun start()
    fun pause()
    fun resume()

    /** Завершает запись и возвращает файл. null, если ничего не записано. */
    suspend fun stop(): RecordedAudio?
    suspend fun discard()
}
