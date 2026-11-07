package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.repository.AudioSource
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import javax.inject.Inject

class ImportAudioUseCase @Inject constructor(
    private val audioSource: AudioSource,
    private val repository: SessionRepository,
    private val queue: ProcessingQueue,
) {
    data class Result(val imported: List<Long>, val failed: Int)

    /** Импортирует файлы и сразу ставит их в очередь с настройками по умолчанию. */
    suspend operator fun invoke(uris: List<String>): Result {
        val ids = mutableListOf<Long>()
        var failed = 0
        for (uri in uris) {
            runCatching { audioSource.import(uri) }
                .onSuccess { audio ->
                    val id = repository.createSession(audio.title, audio.localUri, audio.durationMs)
                    ids += id
                    queue.enqueue(id)
                }
                .onFailure { failed++ }
        }
        return Result(ids, failed)
    }
}
