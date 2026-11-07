package com.lapcevichme.meowscribe.data.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.repository.TranscriptExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JsonTranscriptExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : TranscriptExporter {

    @Serializable
    private data class ExportDocument(val transcript: List<ExportUtterance>)

    @Serializable
    private data class ExportUtterance(
        @SerialName("speaker_id") val speakerId: String,
        @SerialName("start_ms") val startMs: Long,
        @SerialName("end_ms") val endMs: Long,
        val text: String,
    )

    override fun toJson(utterances: List<Utterance>): String {
        val doc = ExportDocument(
            utterances.map {
                ExportUtterance(
                    speakerId = it.speakerId,
                    startMs = it.startMs,
                    endMs = maxOf(it.endMs, it.startMs),
                    text = it.text.trim(),
                )
            },
        )
        return json.encodeToString(doc)
    }

    override suspend fun writeTo(targetUri: String, content: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(Uri.parse(targetUri), "wt").use { out ->
            requireNotNull(out) { "Не удалось открыть файл для записи" }
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    override suspend fun saveToDownloads(fileName: String, content: String, mimeType: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBDIR/"
                // Повторный экспорт той же записи перезаписывает наш файл, а не плодит «(1)», «(2)»…
                val existing = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf(fileName, relativePath),
                    null,
                )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
                val uri = existing ?: requireNotNull(
                    resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, mimeType)
                            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                        },
                    ),
                )
                resolver.openOutputStream(uri, "wt").use { requireNotNull(it).write(content.toByteArray(Charsets.UTF_8)) }
                // Имя могло измениться, если файл с таким же именем создало другое приложение.
                val actualName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fileName
                "$relativePath$actualName"
            } else {
                // Android 9: без разрешения на запись в общие папки — кладём в папку приложения.
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), DOWNLOADS_SUBDIR).apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeText(content, Charsets.UTF_8)
                file.absolutePath
            }
        }.getOrNull()
    }

    override suspend fun shareableUri(fileName: String, content: String): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }

    private companion object {
        const val DOWNLOADS_SUBDIR = "MeowScribe"
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
