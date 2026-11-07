package com.lapcevichme.meowscribe.domain.processing

import com.lapcevichme.meowscribe.domain.model.ProcessingProgress
import kotlinx.coroutines.flow.StateFlow

/** Очередь обработки. Сессии обрабатываются по одной: модели не помещаются в RAM параллельно. */
interface ProcessingQueue {
    /** sessionId → прогресс. Содержит только сессию, которая обрабатывается прямо сейчас. */
    val progress: StateFlow<Map<Long, ProcessingProgress>>

    /** true, пока есть активная или ожидающая работа. */
    val busy: StateFlow<Boolean>

    fun enqueue(sessionId: Long)
    fun cancel(sessionId: Long)

    /** Вернуть в очередь сессии, прерванные смертью процесса. */
    suspend fun resumePending()
}
