package com.lapcevichme.meowscribe.domain.repository

import com.lapcevichme.meowscribe.domain.model.Utterance

interface TranscriptExporter {
    /** JSON строго по схеме ТЗ: {"transcript":[{speaker_id,start_ms,end_ms,text}]}. */
    fun toJson(utterances: List<Utterance>): String

    /** Записать текст в выбранный пользователем документ (SAF CreateDocument). */
    suspend fun writeTo(targetUri: String, content: String)

    /** Сохранить в Загрузки/MeowScribe. Возвращает человекочитаемый путь или null при ошибке. */
    suspend fun saveToDownloads(fileName: String, content: String, mimeType: String = "application/json"): String?

    /** Записать во временный файл для «Поделиться». Возвращает content:// uri. */
    suspend fun shareableUri(fileName: String, content: String): String
}
