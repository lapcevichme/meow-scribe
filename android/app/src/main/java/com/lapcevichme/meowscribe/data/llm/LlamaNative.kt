package com.lapcevichme.meowscribe.data.llm

/** JNI к llama.cpp (src/main/cpp/llama_jni.cpp). Не потокобезопасен: один хэндл — один поток. */
internal object LlamaNative {
    init {
        System.loadLibrary("meowllm")
    }

    fun interface TokenCallback {
        /** @return false — прервать генерацию. */
        fun onToken(generated: Int): Boolean
    }

    /** @return хэндл или 0 при ошибке. */
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long

    external fun nativeCountTokens(handle: Long, text: String): Int

    /** @return UTF-8 ответа или null при ошибке / отмене. */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: TokenCallback?,
    ): ByteArray?

    external fun nativeFree(handle: Long)
}
