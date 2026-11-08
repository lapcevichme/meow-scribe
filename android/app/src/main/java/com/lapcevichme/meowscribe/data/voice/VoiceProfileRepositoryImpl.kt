package com.lapcevichme.meowscribe.data.voice

import com.lapcevichme.meowscribe.data.db.SessionVoiceEntity
import com.lapcevichme.meowscribe.data.db.VoiceDao
import com.lapcevichme.meowscribe.data.db.VoiceProfileEntity
import com.lapcevichme.meowscribe.domain.repository.VoiceProfileRepository
import com.lapcevichme.meowscribe.domain.voice.StoredVoice
import com.lapcevichme.meowscribe.domain.voice.VoiceMatch
import com.lapcevichme.meowscribe.domain.voice.VoiceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceProfileRepositoryImpl @Inject constructor(
    private val dao: VoiceDao,
) : VoiceProfileRepository {

    override fun observeProfiles(): Flow<List<VoiceProfile>> = dao.observeProfiles().map { rows ->
        rows.map { VoiceProfile(it.id, it.name, it.samples, it.updatedAt) }
    }

    override suspend fun storedVoices(): List<StoredVoice> = dao.all().map { it.toStored() }

    // Сравниваем в Kotlin: COLLATE NOCASE в SQLite не понимает кириллицу.
    override suspend fun findByName(name: String): StoredVoice? {
        val key = name.trim().lowercase()
        return dao.all().firstOrNull { it.name.trim().lowercase() == key }?.toStored()
    }

    override suspend fun create(name: String, embedding: FloatArray): Long {
        val now = System.currentTimeMillis()
        return dao.insert(VoiceProfileEntity(name = name.trim(), embedding = embedding.toBytes(), samples = 1, createdAt = now, updatedAt = now))
    }

    override suspend fun update(id: Long, name: String, embedding: FloatArray, samples: Int) =
        dao.update(id, name.trim(), embedding.toBytes(), samples, System.currentTimeMillis())

    override suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim(), System.currentTimeMillis())

    override suspend fun delete(id: Long) = dao.delete(id)

    override fun observeMatches(sessionId: Long): Flow<Map<String, VoiceMatch>> =
        dao.observeSessionVoices(sessionId).map { rows ->
            rows.associate { it.speakerId to VoiceMatch(it.speakerId, it.profileId, it.name, it.similarity) }
        }

    override suspend fun replaceMatches(sessionId: Long, matches: List<VoiceMatch>) =
        dao.replaceSessionVoices(sessionId, matches.map { it.toEntity(sessionId) })

    override suspend fun link(sessionId: Long, match: VoiceMatch) = dao.upsertSessionVoice(match.toEntity(sessionId))

    override suspend fun unlink(sessionId: Long, speakerId: String) = dao.deleteSessionVoice(sessionId, speakerId)

    override suspend fun appearances(profileId: Long): List<Pair<Long, String>> =
        dao.linksOf(profileId).map { it.sessionId to it.speakerId }

    private fun VoiceMatch.toEntity(sessionId: Long) = SessionVoiceEntity(sessionId, speakerId, profileId, similarity)

    private fun VoiceProfileEntity.toStored() = StoredVoice(id, name, samples, embedding.toFloats())

    private fun FloatArray.toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(this)
        return buf.array()
    }

    private fun ByteArray.toFloats(): FloatArray {
        val fb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}
