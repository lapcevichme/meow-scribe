package com.lapcevichme.meowscribe.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceDao {
    @Query("SELECT id, name, samples, updatedAt FROM voice_profiles ORDER BY name")
    fun observeProfiles(): Flow<List<VoiceProfileRow>>

    @Query("SELECT * FROM voice_profiles")
    suspend fun all(): List<VoiceProfileEntity>

    @Insert
    suspend fun insert(profile: VoiceProfileEntity): Long

    @Query("UPDATE voice_profiles SET name = :name, embedding = :embedding, samples = :samples, updatedAt = :now WHERE id = :id")
    suspend fun update(id: Long, name: String, embedding: ByteArray, samples: Int, now: Long)

    @Query("UPDATE voice_profiles SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("DELETE FROM voice_profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT sv.speakerId, sv.profileId, p.name, sv.similarity FROM session_voices sv " +
            "JOIN voice_profiles p ON p.id = sv.profileId WHERE sv.sessionId = :sessionId",
    )
    fun observeSessionVoices(sessionId: Long): Flow<List<SessionVoiceRow>>

    @Upsert
    suspend fun upsertSessionVoice(item: SessionVoiceEntity)

    @Query("DELETE FROM session_voices WHERE sessionId = :sessionId")
    suspend fun clearSessionVoices(sessionId: Long)

    @Query("DELETE FROM session_voices WHERE sessionId = :sessionId AND speakerId = :speakerId")
    suspend fun deleteSessionVoice(sessionId: Long, speakerId: String)

    @Transaction
    suspend fun replaceSessionVoices(sessionId: Long, items: List<SessionVoiceEntity>) {
        clearSessionVoices(sessionId)
        items.forEach { upsertSessionVoice(it) }
    }
}
