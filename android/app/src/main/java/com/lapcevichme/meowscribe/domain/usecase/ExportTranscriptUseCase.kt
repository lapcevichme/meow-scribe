package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.TranscriptExporter
import javax.inject.Inject

class ExportTranscriptUseCase @Inject constructor(
    private val repository: SessionRepository,
    private val exporter: TranscriptExporter,
) {
    suspend fun json(sessionId: Long): String = exporter.toJson(repository.getTranscript(sessionId))

    suspend fun fileName(sessionId: Long): String {
        val title = repository.getSession(sessionId)?.title ?: "transcript"
        return exportFileName(title)
    }

    suspend fun toUri(sessionId: Long, targetUri: String) = exporter.writeTo(targetUri, json(sessionId))

    suspend fun toDownloads(sessionId: Long): String? =
        exporter.saveToDownloads(fileName(sessionId), json(sessionId))

    suspend fun shareUri(sessionId: Long): String = exporter.shareableUri(fileName(sessionId), json(sessionId))

    companion object {
        fun exportFileName(title: String): String {
            // Отрезаем только настоящее расширение (".m4a"), а не точку внутри названия ("7 нояб., 17:21").
            val base = title.replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifEmpty { "transcript" }
            return "$base.json"
        }
    }
}
