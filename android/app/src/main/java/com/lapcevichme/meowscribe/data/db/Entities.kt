package com.lapcevichme.meowscribe.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lapcevichme.meowscribe.domain.model.Profile
import com.lapcevichme.meowscribe.domain.model.SessionStatus

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val audioUri: String,
    val durationMs: Long?,
    val createdAt: Long,
    val status: SessionStatus,
    val speakerCount: Int = 0,
    val processingMs: Long? = null,
    val errorMessage: String? = null,
    val summary: String? = null,
    val profile: Profile = Profile.FAST,
    val language: String = "ru",
    val numSpeakers: Int? = null,
    val useLlm: Boolean = true,
)

@Entity(
    tableName = "utterances",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "idx"], unique = true)],
)
data class UtteranceEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: Long,
    val idx: Int,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Слова с таймстемпами, JSON-массив [{t,s,e}]. */
    val wordsJson: String,
)

@Entity(
    tableName = "speakers",
    primaryKeys = ["sessionId", "speakerId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SpeakerEntity(
    val sessionId: Long,
    val speakerId: String,
    val name: String,
)

/** Проекция для поиска по тексту расшифровок. */
data class SessionText(val sessionId: Long, val text: String)

/** Общая база голосов. embedding — нормированный float32 little-endian. */
@Entity(tableName = "voice_profiles")
data class VoiceProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val embedding: ByteArray,
    val samples: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Профиль без вектора — для списка в настройках. */
data class VoiceProfileRow(val id: Long, val name: String, val samples: Int, val updatedAt: Long)

/** Какой спикер записи узнан как сохранённый голос (или привязан вручную, similarity = null). */
@Entity(
    tableName = "session_voices",
    primaryKeys = ["sessionId", "speakerId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VoiceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class SessionVoiceEntity(
    val sessionId: Long,
    val speakerId: String,
    val profileId: Long,
    val similarity: Float?,
)

data class SessionVoiceRow(val speakerId: String, val profileId: Long, val name: String, val similarity: Float?)
