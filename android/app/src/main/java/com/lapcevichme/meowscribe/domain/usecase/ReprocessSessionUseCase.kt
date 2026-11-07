package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Повторная обработка готовой записи с другими настройками (язык, профиль, число спикеров).
 * Аудио уже лежит внутри приложения, так что заново импортировать файл не нужно.
 */
class ReprocessSessionUseCase @Inject constructor(
    private val repository: SessionRepository,
    private val queue: ProcessingQueue,
) {
    /** @return false, если запись уже в очереди или обрабатывается. */
    suspend operator fun invoke(sessionId: Long, config: PipelineConfig): Boolean {
        val session = repository.getSession(sessionId) ?: return false
        if (session.status == SessionStatus.QUEUED || session.status == SessionStatus.PROCESSING) return false
        // Сначала снимаем сводку из очереди, иначе старая сводка может записаться поверх сброса.
        queue.cancel(sessionId)
        repository.resetForReprocess(sessionId, config)
        // Статус сразу, а не из очереди асинхронно: экран прогресса не должен мигнуть «Готово».
        repository.setStatus(sessionId, SessionStatus.QUEUED)
        queue.enqueue(sessionId)
        return true
    }
}
