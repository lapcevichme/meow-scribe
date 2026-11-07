package com.lapcevichme.meowscribe.domain.processing

import com.lapcevichme.meowscribe.domain.model.ProcessingProgress
import com.lapcevichme.meowscribe.domain.pipeline.Stage
import kotlinx.coroutines.flow.StateFlow

/** Очередь обработки. Сессии обрабатываются по одной: модели не помещаются в RAM параллельно. */
interface ProcessingQueue {
    /** sessionId → прогресс. Содержит только сессию, которая обрабатывается прямо сейчас. */
    val progress: StateFlow<Map<Long, ProcessingProgress>>

    /**
     * Записи, для которых сводка LLM ждёт очереди или считается. Сводки идут с низким приоритетом:
     * только когда нет расшифровок в очереди. Считается та, у которой в [progress] стадия LLM.
     */
    val summaries: StateFlow<Set<Long>>

    /**
     * Записи, которые отменили, но обработка ещё не остановилась. Нативные шаги (диаризация sherpa)
     * проверяют отмену только в своих контрольных точках, поэтому остановка бывает небыстрой.
     */
    val cancelling: StateFlow<Set<Long>>

    /** true, пока есть активная или ожидающая работа (включая сводки). */
    val busy: StateFlow<Boolean>

    fun enqueue(sessionId: Long)
    fun cancel(sessionId: Long)

    /**
     * Поставить в очередь сводку уже готовой записи (например, после правок текста).
     * @return false, если LLM нет в сборке.
     */
    fun summarize(sessionId: Long): Boolean

    /** Вернуть в очередь сессии, прерванные смертью процесса. */
    suspend fun resumePending()
}

/** Состояние сводки LLM для готовой записи. */
enum class SummaryState { QUEUED, RUNNING }

fun summaryState(
    sessionId: Long,
    summaries: Set<Long>,
    progress: Map<Long, ProcessingProgress>,
): SummaryState? = when {
    sessionId !in summaries -> null
    progress[sessionId]?.stage == Stage.LLM -> SummaryState.RUNNING
    else -> SummaryState.QUEUED
}
