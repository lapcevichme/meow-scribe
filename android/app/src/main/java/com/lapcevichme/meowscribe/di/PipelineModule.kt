package com.lapcevichme.meowscribe.di

import com.lapcevichme.meowscribe.data.pipeline.fake.FakePipeline
import com.lapcevichme.meowscribe.domain.pipeline.TranscriptionPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    /** Точка переключения: FakePipeline → SherpaPipeline, когда он будет готов. */
    @Provides
    @Singleton
    fun provideTranscriptionPipeline(): TranscriptionPipeline = FakePipeline()
}
