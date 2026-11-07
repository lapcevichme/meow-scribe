package com.lapcevichme.meowscribe.data.llm

import android.util.Log
import com.lapcevichme.meowscribe.data.models.ModelLocator
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val locator: ModelLocator,
) {
    private val mutex = Mutex()

    /** Есть ли .gguf в APK (на случай сборки без LLM). */
    fun isAvailable(): Boolean = locator.llmAssets().isNotEmpty()

    /**
     * @param onProgress 0..1 — грубая оценка по числу сгенерированных токенов.
     * @return null, если транскрипт пустой, LLM нет в сборке или модель не справилась с форматом.
     */
    suspend fun summarize(
        utterances: List<Utterance>,
        onProgress: (Float) -> Unit = {},
    ): ConversationSummary? = mutex.withLock {
        if (utterances.none { it.text.isNotBlank() }) return@withLock null
        val file = locator.llmFile() ?: return@withLock null
        withContext(Dispatchers.Default) {
            LlamaModel.load(file.path, contextSize = N_CTX).use { llm ->
                val prompt = buildPrompt(fitTranscript(llm, utterances))
                val ctx = currentCoroutineContext()
                ctx.ensureActive()
                var polls = 0
                val raw = llm.generate(prompt, maxTokens = MAX_TOKENS) {
                    onProgress(((++polls).toFloat() / EXPECTED_TOKENS).coerceAtMost(0.99f))
                    !ctx.isActive
                }
                ctx.ensureActive()
                Log.i(TAG, "LLM raw: $raw")
                parse(PREFILL + raw).also { onProgress(1f) }
            }
        }
    }

    /** Транскрипт в виде «Спикер N: текст», урезанный по токенам под контекст. */
    private fun fitTranscript(llm: LlamaModel, utterances: List<Utterance>): String {
        val lines = utterances.filter { it.text.isNotBlank() }.map { u ->
            "Спикер ${u.speakerId.substringAfter("speaker_")}: ${u.text.trim()}"
        }
        var text = lines.joinToString("\n")
        val budget = N_CTX - MAX_TOKENS - PROMPT_OVERHEAD
        val n = llm.countTokens(text)
        if (n > budget) {
            // длинная запись: оставляем начало и конец — там обычно цель и итоги разговора
            val keep = (text.length.toLong() * budget / n).toInt()
            text = text.take(keep * 2 / 3) + "\n…\n" + text.takeLast(keep / 3)
        }
        return text
    }

    /**
     * ChatML вручную, а не chat-шаблон из GGUF: шаблон Qwen3.5 включает «размышления», а нам нужен
     * пустой блок <think> и начало ответа «Название:», чтобы 0.8B держала формат.
     */
    private fun buildPrompt(transcript: String): String =
        "<|im_start|>system\n$SYSTEM<|im_end|>\n" +
            "<|im_start|>user\nРасшифровка разговора:\n\n$transcript<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n$PREFILL"

    companion object {
        private const val TAG = "LlmSummarizer"

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
