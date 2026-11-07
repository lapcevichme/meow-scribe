package com.lapcevichme.meowscribe.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, UtteranceEntity::class, SpeakerEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MeowDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
}
