package com.lapcevichme.meowscribe.domain.model

enum class SessionStatus { QUEUED, PROCESSING, DONE, ERROR }

/** Одна импортированная или записанная аудиозапись и состояние её обработки. */
data class Session(
    val id: Long,
    val title: String,
    /** Локальная копия аудио внутри приложения (file://…). */
    val audioUri: String,
    val durationMs: Long?,
    val createdAt: Long,
    val status: SessionStatus,
    val speakerCount: Int,
    val processingMs: Long?,
    val errorMessage: String?,
    /** Сводка от LLM. Заложено в схему, в UI показывается только если не null. */
    val summary: String?,
    /** С какими настройками запись обрабатывается: фиксируется при создании, повтор идёт с ними же. */
    val config: PipelineConfig = PipelineConfig(),
) {
    val rtf: Double?
        get() = if (processingMs != null && durationMs != null && durationMs > 0) {
            processingMs.toDouble() / durationMs
        } else {
            null
        }
}
