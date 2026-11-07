package com.lapcevichme.meowscribe.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT id FROM sessions WHERE status IN ('QUEUED', 'PROCESSING') ORDER BY createdAt, id")
    suspend fun pendingIds(): List<Long>

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: SessionStatus, error: String?)

    @Query("UPDATE sessions SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: Long, durationMs: Long)

    @Query("UPDATE sessions SET status = 'DONE', errorMessage = NULL, speakerCount = :speakers, processingMs = :processingMs WHERE id = :id")
    suspend fun markDone(id: Long, speakers: Int, processingMs: Long)

    @Query("UPDATE sessions SET speakerCount = :speakers WHERE id = :id")
    suspend fun updateSpeakerCount(id: Long, speakers: Int)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM utterances WHERE sessionId = :sessionId ORDER BY idx")
    fun observeUtterances(sessionId: Long): Flow<List<UtteranceEntity>>

    @Query("SELECT * FROM utterances WHERE sessionId = :sessionId ORDER BY idx")
    suspend fun getUtterances(sessionId: Long): List<UtteranceEntity>

    @Query("DELETE FROM utterances WHERE sessionId = :sessionId")
    suspend fun deleteUtterances(sessionId: Long)

    @Insert
    suspend fun insertUtterances(items: List<UtteranceEntity>)

    @Query("UPDATE utterances SET text = :text, speakerId = :speakerId WHERE sessionId = :sessionId AND idx = :idx")
    suspend fun updateUtterance(sessionId: Long, idx: Int, text: String, speakerId: String)

    @Query("SELECT COUNT(DISTINCT speakerId) FROM utterances WHERE sessionId = :sessionId")
    suspend fun countSpeakers(sessionId: Long): Int

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId")
    fun observeSpeakers(sessionId: Long): Flow<List<SpeakerEntity>>

    @Upsert
    suspend fun upsertSpeaker(speaker: SpeakerEntity)

    @Query("DELETE FROM speakers WHERE sessionId = :sessionId AND speakerId = :speakerId")
    suspend fun deleteSpeaker(sessionId: Long, speakerId: String)

    @Transaction
    suspend fun replaceResult(sessionId: Long, items: List<UtteranceEntity>, speakers: Int, processingMs: Long) {
        deleteUtterances(sessionId)
        insertUtterances(items)
        markDone(sessionId, speakers, processingMs)
    }

    @Query("SELECT sessionId, text FROM utterances ORDER BY sessionId, idx")
    fun observeAllTexts(): Flow<List<SessionText>>

    @Transaction
    suspend fun replaceUtterances(sessionId: Long, items: List<UtteranceEntity>) {
        deleteUtterances(sessionId)
        insertUtterances(items)
        updateSpeakerCount(sessionId, countSpeakers(sessionId))
    }

    @Transaction
    suspend fun editUtterance(sessionId: Long, idx: Int, text: String, speakerId: String) {
        updateUtterance(sessionId, idx, text, speakerId)
        updateSpeakerCount(sessionId, countSpeakers(sessionId))
    }
}
