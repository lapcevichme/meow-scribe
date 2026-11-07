package com.lapcevichme.meowscribe.data.repository

import com.lapcevichme.meowscribe.data.db.SessionDao
import com.lapcevichme.meowscribe.data.db.SessionEntity
import com.lapcevichme.meowscribe.data.db.SpeakerEntity
import com.lapcevichme.meowscribe.data.db.UtteranceEntity
import com.lapcevichme.meowscribe.domain.model.Session
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.model.Word
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val dao: SessionDao,
) : SessionRepository {

    override fun observeSessions(): Flow<List<Session>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeSession(id: Long): Flow<Session?> = dao.observe(id).map { it?.toDomain() }

    override fun observeTranscript(id: Long): Flow<List<Utterance>> =
        dao.observeUtterances(id).map { list -> list.map { it.toDomain() } }

    override fun observeSpeakerNames(id: Long): Flow<Map<String, String>> =
        dao.observeSpeakers(id).map { list -> list.associate { it.speakerId to it.name } }

    override suspend fun getSession(id: Long): Session? = dao.get(id)?.toDomain()

    override suspend fun getTranscript(id: Long): List<Utterance> = dao.getUtterances(id).map { it.toDomain() }

    override suspend fun pendingSessionIds(): List<Long> = dao.pendingIds()

    override suspend fun createSession(title: String, audioUri: String, durationMs: Long?): Long =
        dao.insert(
            SessionEntity(
                title = title,
                audioUri = audioUri,
                durationMs = durationMs,
                createdAt = System.currentTimeMillis(),
                status = SessionStatus.QUEUED,
            ),
        )

    override suspend fun setStatus(id: Long, status: SessionStatus, errorMessage: String?) =
        dao.updateStatus(id, status, errorMessage)

    override suspend fun setDuration(id: Long, durationMs: Long) = dao.updateDuration(id, durationMs)

    override suspend fun saveResult(id: Long, utterances: List<Utterance>, processingMs: Long) {
        val items = utterances.mapIndexed { i, u ->
            UtteranceEntity(
                sessionId = id,
                idx = i,
                speakerId = u.speakerId,
                startMs = u.startMs,
                endMs = u.endMs,
                text = u.text,
                wordsJson = json.encodeToString(u.words.map { WordDto(it.text, it.startMs, it.endMs) }),
            )
        }
        dao.replaceResult(id, items, utterances.map { it.speakerId }.distinct().size, processingMs)
    }

    override suspend fun updateUtterance(id: Long, index: Int, text: String, speakerId: String) =
        dao.editUtterance(id, index, text, speakerId)

    override suspend fun renameSpeaker(id: Long, speakerId: String, name: String?) {
        if (name.isNullOrBlank()) dao.deleteSpeaker(id, speakerId)
        else dao.upsertSpeaker(SpeakerEntity(id, speakerId, name.trim()))
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    @Serializable
    private data class WordDto(val t: String, val s: Long, val e: Long)

    private fun SessionEntity.toDomain() = Session(
        id = id,
        title = title,
        audioUri = audioUri,
        durationMs = durationMs,
        createdAt = createdAt,
        status = status,
        speakerCount = speakerCount,
        processingMs = processingMs,
        errorMessage = errorMessage,
        summary = summary,
    )

    private fun UtteranceEntity.toDomain() = Utterance(
        speakerId = speakerId,
        startMs = startMs,
        endMs = endMs,
        text = text,
        words = runCatching { json.decodeFromString<List<WordDto>>(wordsJson) }
            .getOrDefault(emptyList())
            .map { Word(it.t, it.s, it.e) },
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
