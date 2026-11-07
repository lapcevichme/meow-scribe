package com.lapcevichme.meowscribe.domain.repository

import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Session
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.model.Utterance
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun observeSessions(): Flow<List<Session>>
    fun observeSession(id: Long): Flow<Session?>
    fun observeTranscript(id: Long): Flow<List<Utterance>>

    /** speakerId → отображаемое имя. В экспорт имена не попадают. */
    fun observeSpeakerNames(id: Long): Flow<Map<String, String>>

    /**
     * Поиск по тексту расшифровок без учёта регистра.
     * Возвращает sessionId → первая подходящая реплика (для сниппета в списке).
     */
    fun searchTranscripts(query: String): Flow<Map<Long, String>>

    suspend fun getSession(id: Long): Session?
    suspend fun getTranscript(id: Long): List<Utterance>
    suspend fun getSpeakerNames(id: Long): Map<String, String>
    suspend fun pendingSessionIds(): List<Long>

    suspend fun createSession(title: String, audioUri: String, durationMs: Long?, config: PipelineConfig): Long
    suspend fun setStatus(id: Long, status: SessionStatus, errorMessage: String? = null)
    suspend fun setDuration(id: Long, durationMs: Long)
    suspend fun saveResult(id: Long, utterances: List<Utterance>, processingMs: Long)
    suspend fun setSummary(id: Long, summary: String?)
    /** Новые настройки перед повторной обработкой. Сбрасывает сводку, имена спикеров и узнанные голоса. */
    suspend fun resetForReprocess(id: Long, config: PipelineConfig)

    suspend fun updateUtterance(id: Long, index: Int, text: String, speakerId: String)
    /** Полностью заменяет реплики записи (разделение, слияние). */
    suspend fun replaceTranscript(id: Long, utterances: List<Utterance>)
    suspend fun renameSpeaker(id: Long, speakerId: String, name: String?)
    suspend fun delete(id: Long)
}
