package com.lapcevichme.meowscribe.data.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.lapcevichme.meowscribe.data.models.ModelLocator
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.pipeline.TranscriptionPipeline
import com.lapcevichme.meowscribe.domain.repository.SpeakerEmbedder
import com.lapcevichme.meowscribe.domain.voice.VoiceMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Эмбеддинги той же моделью, что и в диаризации (CAM++), — она уже лежит в APK.
 * Модель грузится на время вызова и сразу выгружается, как и остальные стадии.
 */
@Singleton
class SherpaSpeakerEmbedder @Inject constructor(
    private val locator: ModelLocator,
) : SpeakerEmbedder {

    override val isAvailable: Boolean
        get() = locator.has(ModelLocator.Paths.DIARIZATION_EMBEDDING)

    override suspend fun embed(
        pcm: FloatArray,
        utterances: List<Utterance>,
        speakers: Set<String>?,
    ): Map<String, FloatArray> = withContext(Dispatchers.Default) {
        val bySpeaker = utterances
            .filter { speakers == null || it.speakerId in speakers }
            .groupBy { it.speakerId }
        if (bySpeaker.isEmpty()) return@withContext emptyMap()

        val started = System.currentTimeMillis()
        val extractor = SpeakerEmbeddingExtractor(
            locator.assets,
            SpeakerEmbeddingExtractorConfig(
                model = ModelLocator.Paths.DIARIZATION_EMBEDDING,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            ),
        )
        try {
            val result = LinkedHashMap<String, FloatArray>()
            for ((speakerId, list) in bySpeaker) {
                ensureActive()
                // Самые длинные реплики — самые «чистые»: меньше шансов, что туда попал чужой голос на стыке.
                val stream = extractor.createStream()
                var taken = 0
                for (u in list.sortedByDescending { it.endMs - it.startMs }) {
                    if (taken >= MAX_SAMPLES) break
                    val from = (u.startMs * SAMPLES_PER_MS).toInt().coerceIn(0, pcm.size)
                    val to = (u.endMs * SAMPLES_PER_MS).toInt().coerceIn(from, pcm.size)
                    val len = minOf(to - from, MAX_SAMPLES - taken)
                    if (len < MIN_CHUNK_SAMPLES) continue
                    stream.acceptWaveform(pcm.copyOfRange(from, from + len), TranscriptionPipeline.SAMPLE_RATE)
                    taken += len
                }
                stream.inputFinished()
                if (taken >= MIN_TOTAL_SAMPLES && extractor.isReady(stream)) {
                    result[speakerId] = VoiceMath.normalize(extractor.compute(stream))
                }
                stream.release()
            }
            Log.i(TAG, "эмбеддинги ${result.size}/${bySpeaker.size} спикеров за ${System.currentTimeMillis() - started} мс")
            result
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "MeowVoices"
        const val SAMPLES_PER_MS = TranscriptionPipeline.SAMPLE_RATE / 1000
        /** Больше 20 с на спикера точность почти не добавляет, а время растёт линейно. */
        const val MAX_SAMPLES = 20 * TranscriptionPipeline.SAMPLE_RATE
        const val MIN_CHUNK_SAMPLES = TranscriptionPipeline.SAMPLE_RATE * 3 / 10
        const val MIN_TOTAL_SAMPLES = TranscriptionPipeline.SAMPLE_RATE
    }
}
