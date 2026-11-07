package com.lapcevichme.meowscribe.data.models

import android.content.Context
import android.content.res.AssetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единственное место, где записаны пути к весам. Скачивает их scripts/fetch_models.sh — пути меняем синхронно.
 *
 * sherpa-onnx читает модели прямо из [assets] (они лежат без сжатия), а llama.cpp нужен настоящий файл,
 * поэтому .gguf при первом запуске копируется в filesDir.
 */
@Singleton
class ModelLocator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val assets: AssetManager get() = context.assets

    object Paths {
        const val VAD = "models/vad/silero_vad.onnx"

        const val ASR_DIR = "models/asr/giga-am-v3-ctc-punct"
        const val ASR_MODEL = "$ASR_DIR/model.int8.onnx"
        const val ASR_TOKENS = "$ASR_DIR/tokens.txt"

        const val DIARIZATION_SEGMENTATION = "models/diarization/segmentation.onnx"
        const val DIARIZATION_EMBEDDING = "models/diarization/embedding.onnx"

        const val LLM_DIR = "models/llm"
    }

    data class ModelInfo(val name: String, val path: String, val sizeBytes: Long?) {
        val present: Boolean get() = sizeBytes != null
    }

    /** Что реально лежит в APK — для диагностики и экрана «О приложении». */
    fun inventory(): List<ModelInfo> = listOf(
        ModelInfo("VAD (Silero)", Paths.VAD, assetSize(Paths.VAD)),
        ModelInfo("ASR", Paths.ASR_MODEL, assetSize(Paths.ASR_MODEL)),
        ModelInfo("ASR tokens", Paths.ASR_TOKENS, assetSize(Paths.ASR_TOKENS)),
        ModelInfo("Сегментация спикеров", Paths.DIARIZATION_SEGMENTATION, assetSize(Paths.DIARIZATION_SEGMENTATION)),
        ModelInfo("Эмбеддинги спикеров", Paths.DIARIZATION_EMBEDDING, assetSize(Paths.DIARIZATION_EMBEDDING)),
    ) + llmAssets().map { ModelInfo("LLM", "${Paths.LLM_DIR}/$it", assetSize("${Paths.LLM_DIR}/$it")) }

    fun has(path: String): Boolean = assetSize(path) != null

    /** Размер файла в assets или null, если его нет. Работает, потому что модели лежат без сжатия. */
    fun assetSize(path: String): Long? =
        runCatching { assets.openFd(path).use { it.length } }.getOrNull()
            ?: runCatching { assets.open(path).use { it.available().toLong() } }.getOrNull()

    fun llmAssets(): List<String> =
        assets.list(Paths.LLM_DIR).orEmpty().filter { it.endsWith(".gguf") }.sorted()

    private val copyLock = Mutex()

    /**
     * Путь к .gguf на диске. Копирует из assets при первом вызове (или если размер не совпал — недокопированный файл).
     * null, если LLM в сборку не положили.
     */
    suspend fun llmFile(name: String? = null): File? = copyLock.withLock {
        withContext(Dispatchers.IO) {
            val assetName = name ?: llmAssets().firstOrNull() ?: return@withContext null
            val assetPath = "${Paths.LLM_DIR}/$assetName"
            val expected = assetSize(assetPath) ?: return@withContext null
            val target = File(context.filesDir, assetPath)
            if (target.length() == expected) return@withContext target

            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".part")
            assets.open(assetPath).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            check(tmp.renameTo(target)) { "Не удалось сохранить $assetName" }
            // Старые версии моделей больше не нужны — освобождаем место.
            target.parentFile?.listFiles()?.filter { it != target && it.name.endsWith(".gguf") }?.forEach { it.delete() }
            target
        }
    }
}
