package com.lapcevichme.meowscribe.domain.repository

data class ImportedAudio(
    val title: String,
    /** Локальная копия файла внутри приложения. */
    val localUri: String,
    val durationMs: Long?,
)

class DecodedAudio(
    /** Моно, 16 кГц, float в [-1, 1]. */
    val pcm: FloatArray,
    val durationMs: Long,
)

interface AudioSource {
    /** Копирует внешний файл (SAF / share) во внутреннее хранилище и читает метаданные. */
    suspend fun import(externalUri: String): ImportedAudio

    /** Декодирует любой поддерживаемый формат в моно 16 кГц float. */
    suspend fun decode(localUri: String, onProgress: (Float) -> Unit = {}): DecodedAudio

    suspend fun deleteLocal(localUri: String)
}
