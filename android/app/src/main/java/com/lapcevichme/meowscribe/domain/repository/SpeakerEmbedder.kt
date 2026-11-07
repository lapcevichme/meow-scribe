package com.lapcevichme.meowscribe.domain.repository

import com.lapcevichme.meowscribe.domain.model.Utterance

/** «Отпечаток» голоса: вектор, близкий у одного человека и далёкий у разных. */
interface SpeakerEmbedder {
    /** Есть ли модель эмбеддингов в APK. */
    val isAvailable: Boolean

    /**
     * Нормированный эмбеддинг каждого спикера по его репликам в [pcm] (16 кГц моно).
     * [speakers] = null — для всех. Спикеры, у которых слишком мало речи, в ответ не попадают.
     */
    suspend fun embed(pcm: FloatArray, utterances: List<Utterance>, speakers: Set<String>? = null): Map<String, FloatArray>
}
