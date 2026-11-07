package com.lapcevichme.meowscribe.llama

/**
 * Локальная LLM на llama.cpp (только CPU). Один экземпляр — одна модель и один контекст.
 * Методы блокирующие и тяжёлые: вызывать с Dispatchers.Default. Потокобезопасно за счёт synchronized.
 *
 * ```
 * LlamaModel.load(file.path).use { llm ->
 *     val prompt = llm.chatPrompt(system = "…", user = "…")
 *     val answer = llm.generate(prompt, maxTokens = 256)
 * }
 * ```
 */
class LlamaModel private constructor(private var ptr: Long) : AutoCloseable {

    data class Message(val role: String, val content: String)

    /** Опрашивается на каждом токене: позволяет прервать генерацию при отмене корутины. */
    fun interface Cancellation {
        fun isCancelled(): Boolean
    }

    val contextSize: Int
        @Synchronized get() = nativeContextSize(checkOpen())

    @Synchronized
    fun countTokens(text: String): Int = nativeCountTokens(checkOpen(), text.utf8())

    /** Промпт по встроенному в GGUF chat-шаблону (Qwen, Llama, Gemma… — у каждой свой формат). */
    @Synchronized
    fun applyChatTemplate(messages: List<Message>, addAssistantPrefix: Boolean = true): String =
        nativeApplyChatTemplate(
            checkOpen(),
            messages.map { it.role.utf8() }.toTypedArray(),
            messages.map { it.content.utf8() }.toTypedArray(),
            addAssistantPrefix,
        ).decodeToString()

    fun chatPrompt(system: String?, user: String): String = applyChatTemplate(
        listOfNotNull(system?.let { Message("system", it) }, Message("user", user)),
    )

    /**
     * Жадная генерация продолжения [prompt]. KV-кэш очищается перед каждым вызовом.
     * @throws IllegalArgumentException если промпт + [maxTokens] не влезают в контекст.
     */
    @Synchronized
    fun generate(
        prompt: String,
        maxTokens: Int = 256,
        stop: List<String> = emptyList(),
        cancellation: Cancellation? = null,
    ): String = nativeGenerate(
        checkOpen(),
        prompt.utf8(),
        maxTokens,
        stop.map { it.utf8() }.toTypedArray(),
        cancellation,
    ).decodeToString()

    @Synchronized
    override fun close() {
        if (ptr != 0L) {
            nativeFree(ptr)
            ptr = 0L
        }
    }

    private fun checkOpen(): Long = ptr.also { check(it != 0L) { "LlamaModel уже закрыта" } }

    companion object {
        init {
            System.loadLibrary("meow_llama")
        }

        /**
         * @param path файл .gguf на диске (не в assets: llama.cpp мапит его через mmap).
         * @param contextSize токенов на промпт + ответ. Больше — больше RAM под KV-кэш.
         */
        fun load(path: String, contextSize: Int = 2048, threads: Int = defaultThreads()): LlamaModel =
            LlamaModel(nativeLoad(path, contextSize, threads))

        /** Какие наборы инструкций реально включены (AVX2, DOTPROD…): для диагностики скорости. */
        fun systemInfo(): String = nativeSystemInfo()

        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        private fun String.utf8() = toByteArray(Charsets.UTF_8)

        @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
        @JvmStatic private external fun nativeFree(ptr: Long)
        @JvmStatic private external fun nativeContextSize(ptr: Long): Int
        @JvmStatic private external fun nativeCountTokens(ptr: Long, text: ByteArray): Int
        @JvmStatic private external fun nativeApplyChatTemplate(
            ptr: Long,
            roles: Array<ByteArray>,
            contents: Array<ByteArray>,
            addAssistant: Boolean,
        ): ByteArray

        @JvmStatic private external fun nativeGenerate(
            ptr: Long,
            prompt: ByteArray,
            maxTokens: Int,
            stops: Array<ByteArray>,
            cancellation: Cancellation?,
        ): ByteArray

        @JvmStatic private external fun nativeSystemInfo(): String
    }
}
