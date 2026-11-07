package com.lapcevichme.meowscribe.di

import android.content.Context
import androidx.room.Room
import com.lapcevichme.meowscribe.data.audio.MediaCodecAudioSource
import com.lapcevichme.meowscribe.data.audio.WavAudioRecorder
import com.lapcevichme.meowscribe.data.db.MeowDatabase
import com.lapcevichme.meowscribe.data.db.SessionDao
import com.lapcevichme.meowscribe.data.export.JsonTranscriptExporter
import com.lapcevichme.meowscribe.data.processing.ProcessingManager
import com.lapcevichme.meowscribe.data.repository.SessionRepositoryImpl
import com.lapcevichme.meowscribe.data.settings.PrefsSettingsRepository
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.repository.AudioRecorder
import com.lapcevichme.meowscribe.domain.repository.AudioSource
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.SettingsRepository
import com.lapcevichme.meowscribe.domain.repository.TranscriptExporter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {
    @Binds abstract fun sessionRepository(impl: SessionRepositoryImpl): SessionRepository
    @Binds abstract fun audioSource(impl: MediaCodecAudioSource): AudioSource
    @Binds abstract fun exporter(impl: JsonTranscriptExporter): TranscriptExporter
    @Binds abstract fun processingQueue(impl: ProcessingManager): ProcessingQueue
    @Binds abstract fun settings(impl: PrefsSettingsRepository): SettingsRepository
    @Binds abstract fun recorder(impl: WavAudioRecorder): AudioRecorder
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MeowDatabase =
        Room.databaseBuilder(context, MeowDatabase::class.java, "meow.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun sessionDao(db: MeowDatabase): SessionDao = db.sessionDao()
}
