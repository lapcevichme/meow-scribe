package com.lapcevichme.meowscribe.data.llm

import android.content.Context
import android.util.Log
import com.lapcevichme.meowscribe.domain.model.Utterance
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Результат LLM: название разговора и 3–5 пунктов резюме. Слова транскрипта LLM не трогает. */
data class ConversationSummary(
    val title: String,
    val bullets: List<String>,
)

/**
 * Локальная LLM (Qwen3.5-0.8B, llama.cpp): делает название и краткое резюме по готовому транскрипту.
 *
 * Запускается ПОСЛЕ того, как транскрипт сохранён и экспортирован, поэтому не влияет ни на WER,
 * ни на время до результата. Модель загружается на время одного вызова и сразу выгружается.
 */
@Singleton
class LlmSummarizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Есть ли модель в APK (на случай сборки без LLM). */
    fun isAvailable(): Boolean = runCatching { context.assets.openFd(ASSET_PATH).use { true } }.getOrDefault(false)

    /**
     * @param onProgress 0..1 — грубая оценка по числу сгенерированных токенов.
     * @return null, если транскрипт пустой или модель не справилась с форматом.
     */
    suspend fun summarize(
        utterances: List<Utterance>,
        onProgress: (Float) -> Unit = {},
    ): ConversationSummary? = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (utterances.none { it.text.isNotBlank() }) return@withContext null
            val model = ensureModelFile()
            val handle = LlamaNative.nativeLoad(model.absolutePath, N_CTX, threads)
            check(handle != 0L) { "Не удалось загрузить LLM" }
            try {
                val transcript = fitTranscript(handle, utterances)
                val prompt = buildPrompt(transcript)
                currentCoroutineContext().ensureActive()
                val ctx = currentCoroutineContext()
                val bytes = LlamaNative.nativeGenerate(handle, prompt, MAX_TOKENS, 0f) { n ->
                    onProgress((n.toFloat() / EXPECTED_TOKENS).coerceAtMost(0.99f))
                    ctx.isActive
                }
                ctx.ensureActive()
                val raw = bytes?.toString(Charsets.UTF_8) ?: return@withContext null
                Log.i(TAG, "LLM raw: $raw")
                parse(PREFILL + raw).also { onProgress(1f) }
            } finally {
                LlamaNative.nativeFree(handle)
            }
        }
    }

    /** Транскрипт в виде «Спикер N: текст», урезанный по токенам под контекст. */
    private fun fitTranscript(handle: Long, utterances: List<Utterance>): String {
        val lines = utterances.filter { it.text.isNotBlank() }.map { u ->
            "Спикер ${u.speakerId.substringAfter("speaker_")}: ${u.text.trim()}"
        }
        var text = lines.joinToString("\n")
        val budget = N_CTX - MAX_TOKENS - PROMPT_OVERHEAD
        val n = LlamaNative.nativeCountTokens(handle, text)
        if (n > budget) {
            // длинная запись: оставляем начало и конец — там обычно цель и итоги разговора
            val keep = (text.length.toLong() * budget / n).toInt()
            text = text.take(keep * 2 / 3) + "\n…\n" + text.takeLast(keep / 3)
        }
        return text
    }

    private fun buildPrompt(transcript: String): String =
        "<|im_start|>system\n$SYSTEM<|im_end|>\n" +
            "<|im_start|>user\nРасшифровка разговора:\n\n$transcript<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n$PREFILL"

    /** Копирует GGUF из assets в filesDir один раз (llama.cpp нужен путь к файлу). */
    private fun ensureModelFile(): File {
        val dst = File(context.filesDir, "llm/$FILE_NAME")
        val size = runCatching { context.assets.openFd(ASSET_PATH).use { it.length } }.getOrDefault(-1L)
        if (dst.exists() && (size < 0 || dst.length() == size)) return dst
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".part")
        context.assets.open(ASSET_PATH).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        check(tmp.renameTo(dst)) { "Не удалось сохранить модель LLM" }
        return dst
    }

    companion object {
        private const val TAG = "LlmSummarizer"
        const val FILE_NAME = "qwen3.5-0.8b-q4_k_m.gguf"
        const val ASSET_PATH = "models/llm/$FILE_NAME"

        private const val N_CTX = 4096
        private const val MAX_TOKENS = 320
        private const val EXPECTED_TOKENS = 140
        private const val PROMPT_OVERHEAD = 160
        private const val PREFILL = "Название:"
        private const val SYSTEM =
            "Ты кратко пересказываешь расшифровки разговоров на русском языке. " +
                "Отвечай в формате:\nНазвание: <короткий заголовок>\nРезюме:\n- <пункт>\n- <пункт>\n- <пункт>"

        /** Разбор ответа вида «Название: …\nРезюме:\n- …». */
        internal fun parse(text: String): ConversationSummary? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val title = lines.firstOrNull { it.startsWith("Название", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.trim('"', '«', '»', '*', '.')
                ?.takeIf { it.isNotBlank() && it.length <= 120 }
                ?: return null
            val bullets = lines
                .filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") || it.matches(Regex("^\\d+[.)].*")) }
                .map { it.trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(5)
            return ConversationSummary(title, bullets)
        }
    }
}
