package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.SpeakerEmbedder
import com.lapcevichme.meowscribe.domain.repository.VoiceProfileRepository
import com.lapcevichme.meowscribe.domain.voice.VoiceMath
import javax.inject.Inject

/**
 * После обработки сверяет спикеров записи с сохранёнными голосами и подставляет имена.
 * Если сохранённых голосов нет, ничего не считает — на RTF это не влияет.
 */
class RecognizeVoicesUseCase @Inject constructor(
    private val embedder: SpeakerEmbedder,
    private val voices: VoiceProfileRepository,
    private val sessions: SessionRepository,
) {
    /** Нужно ли вообще запускать распознавание (чтобы не показывать лишний этап). */
    suspend fun isNeeded(): Boolean = embedder.isAvailable && voices.storedVoices().isNotEmpty()

    suspend operator fun invoke(sessionId: Long, pcm: FloatArray, utterances: List<Utterance>) {
        if (!embedder.isAvailable) return
        val profiles = voices.storedVoices()
        if (profiles.isEmpty()) return
        val embeddings = embedder.embed(pcm, utterances)
        val matches = VoiceMath.match(embeddings, profiles)
        voices.replaceMatches(sessionId, matches)
        matches.forEach { sessions.renameSpeaker(sessionId, it.speakerId, it.name) }
    }
}
