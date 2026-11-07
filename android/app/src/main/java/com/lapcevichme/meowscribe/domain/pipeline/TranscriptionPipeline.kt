package com.lapcevichme.meowscribe.domain.pipeline

import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Utterance
import kotlinx.coroutines.flow.Flow

/**
 * Контракт между приложением и ML-пайплайном. Менять только по договорённости обоих.
 * Реализации: data/pipeline/fake/FakePipeline, data/pipeline/sherpa/SherpaPipeline.
 */
interface TranscriptionPipeline {
    /**
     * @param pcm моно, 16 кГц, float в [-1, 1]. Декодирование файла — на стороне приложения.
     * Поток должен завершиться ровно одним [PipelineEvent.Done] или [PipelineEvent.Failed].
     * Отмена корутины-коллектора должна прерывать инференс и освобождать модели.
     */
    fun run(pcm: FloatArray, config: PipelineConfig): Flow<PipelineEvent>

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

enum class Stage { DECODE, VAD, DIARIZATION, ASR, MERGE, LLM }

sealed interface PipelineEvent {
    /** fraction — прогресс внутри стадии, 0..1. */
    data class Progress(val stage: Stage, val fraction: Float, val message: String? = null) : PipelineEvent

    /** Промежуточный результат (опционально) — можно показывать реплики по мере готовности. */
    data class Partial(val utterances: List<Utterance>) : PipelineEvent

    data class Done(val utterances: List<Utterance>, val stats: Stats) : PipelineEvent

    data class Failed(val error: Throwable) : PipelineEvent
}

data class Stats(
    val audioMs: Long,
    val processingMs: Long,
    val peakRamMb: Long? = null,
    val stageTimesMs: Map<Stage, Long> = emptyMap(),
) {
    val rtf: Double get() = if (audioMs > 0) processingMs.toDouble() / audioMs else 0.0
}
