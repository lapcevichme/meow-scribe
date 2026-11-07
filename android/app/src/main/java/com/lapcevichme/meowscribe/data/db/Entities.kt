package com.lapcevichme.meowscribe.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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
