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
     * @param names имена спикеров (переименованные вручную или узнанные по голосу) — чтобы сводка говорила
     *   «Миша», а не «Спикер 1», как подписано в расшифровке
     * @param onProgress 0..1 — грубая оценка по числу сгенерированных токенов.
     * @return null, если транскрипт пустой, LLM нет в сборке или модель не справилась с форматом.
     */
    suspend fun summarize(
        utterances: List<Utterance>,
        names: Map<String, String> = emptyMap(),
        onProgress: (Float) -> Unit = {},
    ): ConversationSummary? = mutex.withLock {
        if (utterances.none { it.text.isNotBlank() }) return@withLock null
        val file = locator.llmFile() ?: return@withLock null
        withContext(Dispatchers.Default) {
            LlamaModel.load(file.path, contextSize = N_CTX).use { llm ->
                val prompt = fitPrompt(llm, utterances, names)
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

    /**
     * Промпт с транскриптом «Спикер N: текст», урезанным так, чтобы промпт целиком и ответ влезли в контекст.
     * Токены не пропорциональны символам (служебные теги, латиница, числа), поэтому меряем готовый промпт
     * и ужимаем, пока не влезет: раньше оценка по символам промахивалась, и на 20-минутной записи
     * llama.cpp отказывалась генерировать («промпт 3883 ток. + ответ не влезают в 4096»).
     */
    private fun fitPrompt(llm: LlamaModel, utterances: List<Utterance>, names: Map<String, String>): String {
        val text = utterances.filter { it.text.isNotBlank() }.joinToString("\n") { u ->
            val who = names[u.speakerId]?.takeIf { it.isNotBlank() } ?: "Спикер ${u.speakerId.substringAfter("speaker_")}"
            "$who: ${u.text.trim()}"
        }
        val budget = N_CTX - MAX_TOKENS - SAFETY_TOKENS
        var keep = text.length
        while (true) {
            // длинная запись: оставляем начало и конец — там обычно цель и итоги разговора
            val cut = if (keep >= text.length) text else text.take(keep * 2 / 3) + "\n…\n" + text.takeLast(keep / 3)
            val prompt = buildPrompt(cut)
            val n = llm.countTokens(prompt)
            if (n <= budget || keep == 0) {
                if (keep < text.length) Log.i(TAG, "транскрипт урезан: ${text.length} → $keep симв., промпт $n ток.")
                return prompt
            }
            keep = (keep.toLong() * budget / n * 9 / 10).toInt().coerceIn(0, keep - 1)
        }
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
        /** Запас на BOS и расхождение подсчёта токенов с токенизацией в generate(). */
        private const val SAFETY_TOKENS = 16
        private const val PREFILL = "Название:"
        private const val SYSTEM =
            "Ты кратко пересказываешь расшифровки разговоров на русском языке. " +
                "Отвечай в формате:\nНазвание: <короткий заголовок>\nРезюме:\n- <пункт>\n- <пункт>\n- <пункт>"

        /** 0.8B иногда переписывает заготовку из формата: «- Пункт: …». */
        private val PLACEHOLDER = Regex("^(пункт|point)\\s*\\d*\\s*[:.—-]\\s*", RegexOption.IGNORE_CASE)

        /** Разбор ответа вида «Название: …\nРезюме:\n- …». */
        internal fun parse(text: String): ConversationSummary? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val title = lines.firstOrNull { it.startsWith("Название", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.trim('"', '«', '»', '*', '.')
                ?.takeIf { it.isNotBlank() && it.length <= 120 }
                ?: return null
            val bullets = lines
                .filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") || it.matches(Regex("^\\d+[.)].*")) }
                .map { it.trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").replace(PLACEHOLDER, "").trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(5)
            return ConversationSummary(title, bullets)
        }
    }
}
