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

        /** Русский: GigaAM v3 RNNT с пунктуацией и регистром (выбор по стенду, bench/README.md). */
        const val ASR_RU_DIR = "models/asr/giga-am-v3-rnnt-punct"
        const val ASR_RU_ENCODER = "$ASR_RU_DIR/encoder.int8.onnx"
        const val ASR_RU_DECODER = "$ASR_RU_DIR/decoder.onnx"
        const val ASR_RU_JOINER = "$ASR_RU_DIR/joiner.onnx"
        const val ASR_RU_TOKENS = "$ASR_RU_DIR/tokens.txt"

        /** Английский: Parakeet TDT 110M int8. */
        const val ASR_EN_DIR = "models/asr/parakeet-tdt-110m-en"
        const val ASR_EN_ENCODER = "$ASR_EN_DIR/encoder.int8.onnx"
        const val ASR_EN_DECODER = "$ASR_EN_DIR/decoder.int8.onnx"
        const val ASR_EN_JOINER = "$ASR_EN_DIR/joiner.int8.onnx"
        const val ASR_EN_TOKENS = "$ASR_EN_DIR/tokens.txt"

        /** Определение языка (language = auto): whisper tiny int8. */
        const val LID_DIR = "models/lid/whisper-tiny"
        const val LID_ENCODER = "$LID_DIR/tiny-encoder.int8.onnx"
        const val LID_DECODER = "$LID_DIR/tiny-decoder.int8.onnx"

        /** fp32: int8-версия на стенде поднимает DER с 6.9% до 11.3%. */
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
        ModelInfo("ASR ru (GigaAM v3)", Paths.ASR_RU_ENCODER, assetSize(Paths.ASR_RU_ENCODER)),
        ModelInfo("ASR en (Parakeet)", Paths.ASR_EN_ENCODER, assetSize(Paths.ASR_EN_ENCODER)),
        ModelInfo("Определение языка", Paths.LID_DECODER, assetSize(Paths.LID_DECODER)),
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
