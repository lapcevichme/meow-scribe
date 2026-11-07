package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.export.ExportDocument
import com.lapcevichme.meowscribe.domain.export.ExportFormat
import com.lapcevichme.meowscribe.domain.export.ExportOptions
import com.lapcevichme.meowscribe.domain.export.TranscriptFormatter
import com.lapcevichme.meowscribe.domain.model.Session
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.TranscriptExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class ExportTranscriptUseCase @Inject constructor(
    private val repository: SessionRepository,
    private val exporter: TranscriptExporter,
) {
    /** Собирает файл из уже загруженных данных — для предпросмотра без обращения к БД. */
    fun render(session: Session, utterances: List<Utterance>, names: Map<String, String>, options: ExportOptions): ExportDocument {
        val fileName = exportFileName(session.title, options.format)
        return when (options.format) {
            ExportFormat.JSON -> ExportDocument(fileName, options.format.mimeType, exporter.toJson(utterances), utterances.size)
            ExportFormat.TXT -> ExportDocument(
                fileName,
                options.format.mimeType,
                TranscriptFormatter.txt(txtHeader(session, utterances), utterances, names, options.speakerNames, options.timestamps),
                utterances.size,
            )
            ExportFormat.SRT -> {
                val cues = TranscriptFormatter.srtCues(utterances, names, options.speakerNames)
                ExportDocument(fileName, options.format.mimeType, TranscriptFormatter.srt(cues), cues.size)
            }
        }
    }

    suspend fun render(sessionId: Long, options: ExportOptions): ExportDocument {
        val session = requireNotNull(repository.getSession(sessionId)) { "Запись не найдена" }
        return render(session, repository.getTranscript(sessionId), repository.getSpeakerNames(sessionId), options)
    }

    suspend fun toUri(doc: ExportDocument, targetUri: String) = exporter.writeTo(targetUri, doc.content)

    suspend fun toDownloads(doc: ExportDocument): String? = exporter.saveToDownloads(doc.fileName, doc.content, doc.mimeType)

    suspend fun shareUri(doc: ExportDocument): String = exporter.shareableUri(doc.fileName, doc.content)

    private fun txtHeader(session: Session, utterances: List<Utterance>): List<String> {
        val date = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")).format(Date(session.createdAt))
        val speakers = utterances.map { it.speakerId }.distinct().size
        val meta = listOfNotNull(
            date,
            session.durationMs?.let(TranscriptFormatter::clock),
            "спикеров: $speakers",
        ).joinToString(" · ")
        return listOf(session.title, meta)
    }

    companion object {
        fun exportFileName(title: String, format: ExportFormat = ExportFormat.JSON): String {
            // Отрезаем только настоящее расширение (".m4a"), а не точку внутри названия ("7 нояб., 17:21").
            val base = title.replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifEmpty { "transcript" }
            return "$base.${format.extension}"
        }
    }
}
