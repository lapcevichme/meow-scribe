package com.lapcevichme.meowscribe.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        UtteranceEntity::class,
        SpeakerEntity::class,
        VoiceProfileEntity::class,
        SessionVoiceEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class MeowDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun voiceDao(): VoiceDao
}

/** v3: общая база голосов. Старые записи сохраняются. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `voice_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `embedding` BLOB NOT NULL, `samples` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `session_voices` (`sessionId` INTEGER NOT NULL, `speakerId` TEXT NOT NULL, " +
                "`profileId` INTEGER NOT NULL, `similarity` REAL, PRIMARY KEY(`sessionId`, `speakerId`), " +
                "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`profileId`) REFERENCES `voice_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_voices_profileId` ON `session_voices` (`profileId`)")
    }
}
