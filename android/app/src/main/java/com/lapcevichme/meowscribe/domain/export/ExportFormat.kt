package com.lapcevichme.meowscribe.domain.export

enum class ExportFormat(val extension: String, val mimeType: String) {
    /** Строго по схеме конкурса — для проверки и других программ. */
    JSON("json", "application/json"),
    /** Читаемый текст с именами — для писем и заметок. */
    TXT("txt", "text/plain"),
    /** Субтитры для видеоплееров. */
    SRT("srt", "application/x-subrip"),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.JSON,
    /** Имена из приложения вместо speaker_N (в JSON не применяется: схема требует speaker_id). */
    val speakerNames: Boolean = true,
    /** Тайм-коды перед репликами (только TXT). */
    val timestamps: Boolean = true,
)

/** Готовый к записи файл. */
data class ExportDocument(
    val fileName: String,
    val mimeType: String,
    val content: String,
    /** Сколько единиц внутри: реплик для JSON/TXT, субтитров для SRT. */
    val items: Int,
)
