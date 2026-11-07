package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.repository.AudioSource
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.SpeakerEmbedder
import com.lapcevichme.meowscribe.domain.repository.VoiceProfileRepository
import com.lapcevichme.meowscribe.domain.voice.VoiceMatch
import com.lapcevichme.meowscribe.domain.voice.VoiceMath
import javax.inject.Inject

/**
 * «Запомнить голос»: считает эмбеддинг спикера по текущей (возможно, исправленной вручную) расшифровке
 * и добавляет его в общую базу. Ключ голоса — имя: если голос с таким именем уже есть,
 * новый вектор усредняется с ним, так что узнавание улучшается с каждой записью.
 */
class RememberVoiceUseCase @Inject constructor(
    private val embedder: SpeakerEmbedder,
    private val voices: VoiceProfileRepository,
    private val sessions: SessionRepository,
    private val audio: AudioSource,
) {
    sealed interface Result {
        data class Created(val name: String) : Result
        data class Updated(val name: String, val samples: Int) : Result
        data object TooLittleSpeech : Result
        data object NoModel : Result
    }

    suspend operator fun invoke(sessionId: Long, speakerId: String, name: String): Result {
        if (!embedder.isAvailable) return Result.NoModel
        val session = sessions.getSession(sessionId) ?: return Result.TooLittleSpeech
        val transcript = sessions.getTranscript(sessionId)
        val pcm = audio.decode(session.audioUri).pcm
        val embedding = embedder.embed(pcm, transcript, setOf(speakerId))[speakerId] ?: return Result.TooLittleSpeech

        val existing = voices.findByName(name)
        val result: Result
        val profileId = if (existing != null) {
            val samples = existing.samples + 1
            voices.update(existing.id, name, VoiceMath.mergeCentroid(existing.embedding, existing.samples, embedding), samples)
            result = Result.Updated(name, samples)
            existing.id
        } else {
            result = Result.Created(name)
            voices.create(name, embedding)
        }
        voices.link(sessionId, VoiceMatch(speakerId, profileId, name, similarity = null))
        sessions.renameSpeaker(sessionId, speakerId, name)
        return result
    }
}
