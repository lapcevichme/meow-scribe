package com.lapcevichme.meowscribe.di

import com.lapcevichme.meowscribe.data.pipeline.sherpa.SherpaPipeline
import com.lapcevichme.meowscribe.domain.pipeline.TranscriptionPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    /** Точка переключения: FakePipeline (UI без моделей) ↔ SherpaPipeline (настоящий инференс). */
    @Provides
    @Singleton
    fun provideTranscriptionPipeline(impl: SherpaPipeline): TranscriptionPipeline = impl
}
