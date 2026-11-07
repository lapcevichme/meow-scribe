package com.lapcevichme.meowscribe.domain.model

import com.lapcevichme.meowscribe.domain.pipeline.Stage

/** Живой прогресс обработки одной сессии. Существует только пока сессия в работе. */
data class ProcessingProgress(
    val sessionId: Long,
    val stage: Stage,
    val stageFraction: Float,
    val overallFraction: Float,
    val message: String?,
    val partial: List<Utterance>,
    val elapsedMs: Long,
    val audioMs: Long?,
    val peakRamMb: Long?,
    /** Длительность завершённых стадий, мс. */
    val stageTimesMs: Map<Stage, Long>,
)
