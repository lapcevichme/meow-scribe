package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.repository.AudioSource
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.SettingsRepository
import javax.inject.Inject

/** Создаёт сессии из файлов или записи с микрофона и ставит их в очередь с текущими настройками. */
class ImportAudioUseCase @Inject constructor(
    private val audioSource: AudioSource,
    private val repository: SessionRepository,
    private val settings: SettingsRepository,
    private val queue: ProcessingQueue,
) {
    data class Result(val imported: List<Long>, val failed: Int)

    suspend operator fun invoke(uris: List<String>): Result {
        val ids = mutableListOf<Long>()
        var failed = 0
        for (uri in uris) {
            runCatching { audioSource.import(uri) }
                .onSuccess { audio -> ids += addLocal(audio.title, audio.localUri, audio.durationMs) }
                .onFailure { failed++ }
        }
        return Result(ids, failed)
    }

    /** Файл уже лежит внутри приложения (например, запись с микрофона). */
    suspend fun addLocal(title: String, localUri: String, durationMs: Long?): Long {
        val id = repository.createSession(title, localUri, durationMs, settings.config.value)
        queue.enqueue(id)
        return id
    }
}
