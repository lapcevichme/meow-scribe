package com.lapcevichme.meowscribe.domain.repository

import com.lapcevichme.meowscribe.domain.voice.StoredVoice
import com.lapcevichme.meowscribe.domain.voice.VoiceMatch
import com.lapcevichme.meowscribe.domain.voice.VoiceProfile
import kotlinx.coroutines.flow.Flow

/** Общая для всех записей база голосов. */
interface VoiceProfileRepository {
    fun observeProfiles(): Flow<List<VoiceProfile>>
    suspend fun storedVoices(): List<StoredVoice>
    suspend fun findByName(name: String): StoredVoice?

    suspend fun create(name: String, embedding: FloatArray): Long
    suspend fun update(id: Long, name: String, embedding: FloatArray, samples: Int)
    suspend fun rename(id: Long, name: String)
    suspend fun delete(id: Long)

    /** speakerId → какой сохранённый голос узнан или привязан в этой записи. */
    fun observeMatches(sessionId: Long): Flow<Map<String, VoiceMatch>>
    /** Заменяет все привязки записи (после обработки). */
    suspend fun replaceMatches(sessionId: Long, matches: List<VoiceMatch>)
    suspend fun link(sessionId: Long, match: VoiceMatch)
    suspend fun unlink(sessionId: Long, speakerId: String)
}
