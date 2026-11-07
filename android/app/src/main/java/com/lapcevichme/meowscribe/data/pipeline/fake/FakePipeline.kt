package com.lapcevichme.meowscribe.data.pipeline.fake

import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.model.Word
import com.lapcevichme.meowscribe.domain.pipeline.PipelineEvent
import com.lapcevichme.meowscribe.domain.pipeline.Stage
import com.lapcevichme.meowscribe.domain.pipeline.Stats
import com.lapcevichme.meowscribe.domain.pipeline.TranscriptionPipeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Заглушка для разработки UI: имитирует стадии с задержками и отдаёт фиксированный диалог,
 * растянутый на длительность переданного аудио (чтобы работала синхронизация с плеером).
 */
class FakePipeline(
    private val stepDelayMs: Long = 120,
) : TranscriptionPipeline {

    override fun run(pcm: FloatArray, config: PipelineConfig): Flow<PipelineEvent> = flow {
        val started = System.currentTimeMillis()
        val audioMs = pcm.size * 1000L / TranscriptionPipeline.SAMPLE_RATE
        val stageTimes = mutableMapOf<Stage, Long>()

        val stages = buildList {
            addAll(listOf(Stage.VAD, Stage.DIARIZATION, Stage.ASR, Stage.MERGE))
            if (config.useLlm) add(Stage.LLM)
        }
        val utterances = buildDialog(audioMs.coerceAtLeast(DIALOG.size * 2_000L))

        for (stage in stages) {
            val stageStart = System.currentTimeMillis()
            val steps = if (stage == Stage.ASR) utterances.size else 10
            for (i in 1..steps) {
                delay(stepDelayMs)
                val msg = if (stage == Stage.ASR) "Чанк $i/$steps" else null
                emit(PipelineEvent.Progress(stage, i.toFloat() / steps, msg))
                if (stage == Stage.ASR) emit(PipelineEvent.Partial(utterances.take(i)))
            }
            stageTimes[stage] = System.currentTimeMillis() - stageStart
        }

        emit(
            PipelineEvent.Done(
                utterances = utterances,
                stats = Stats(
                    audioMs = audioMs,
                    processingMs = System.currentTimeMillis() - started,
                    peakRamMb = null,
                    stageTimesMs = stageTimes,
                ),
            ),
        )
    }

    private fun buildDialog(totalMs: Long): List<Utterance> {
        val slot = totalMs / DIALOG.size
        return DIALOG.mapIndexed { i, (speaker, text) ->
            val start = i * slot + 150
            val end = (i + 1) * slot - 250
            val tokens = text.split(" ")
            val wordSlot = (end - start) / tokens.size
            Utterance(
                speakerId = speaker,
                startMs = start,
                endMs = end,
                text = text,
                words = tokens.mapIndexed { w, t ->
                    Word(t, start + w * wordSlot, start + (w + 1) * wordSlot)
                },
            )
        }
    }

    private companion object {
        val DIALOG = listOf(
            "speaker_1" to "Добрый день! Вы не видели мою миску?",
            "speaker_2" to "Мяу. Какую ещё миску?",
            "speaker_1" to "Синюю, с рыбками. Она стояла на кухне.",
            "speaker_2" to "А, эту. Я её случайно уронил под диван.",
            "speaker_3" to "Можно потише? Я пытаюсь спать.",
            "speaker_1" to "Извини. Мы уже заканчиваем.",
            "speaker_2" to "Хм, ага. Пойдём доставать.",
        )
    }
}
