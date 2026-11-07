package com.lapcevichme.meowscribe.data.pipeline.sherpa

/**
 * Переоценка спикера целых предложений по голосу.
 *
 * На быстром диалоге диаризация склеивает соседние реплики разных людей в один длинный сегмент
 * («Здравствуйте! — Спасибо. — Расскажи, пожалуйста…» целиком у одного спикера). Метка у всех слов
 * одна, и склейка слов тут не поможет. Пунктуация ASR даёт границы предложений: для каждого предложения
 * от `minSec` с считаем эмбеддинг CAM++ и, если он ближе к центроиду другого крупного спикера
 * (доля речи ≥ `majorShare`) с перевесом ≥ `margin`, отдаём предложение целиком ему.
 *
 * Стенд AMI (9 окон по 4 мин, вместе с [WordMerger.assignBackchannels] и сглаживанием 1 с):
 * cpWER 45.9 → 44.6%, DER 32.8 → 32.2%, точность спикера по словам 87.6 → 88.6%, WER без изменений.
 * Логика 1:1 с relabel_sentences() в bench/pipeline.py.
 */
internal object SentenceRelabeler {

    /** Предложения, по которым стоит считать эмбеддинг: от [minSec] с. */
    fun candidates(words: List<TimedWord>, minSec: Float): List<IntRange> =
        WordMerger.sentenceRanges(words).filter { words[it.last].end - words[it.first].start >= minSec }

    /**
     * @param ranges предложения из [candidates]
     * @param embeddings нормированный эмбеддинг каждого предложения (null — не посчитался)
     * @param centroids нормированный эмбеддинг каждого спикера по его речи
     * @param durations суммарная речь каждого спикера по сегментам диаризации, с
     */
    fun relabel(
        words: List<TimedWord>,
        ranges: List<IntRange>,
        embeddings: List<FloatArray?>,
        centroids: Map<Int, FloatArray>,
        durations: Map<Int, Float>,
        margin: Float,
        majorShare: Float,
    ): List<TimedWord> {
        val total = durations.values.sum().takeIf { it > 0f } ?: return words
        val major = centroids.keys.filter { (durations[it] ?: 0f) / total >= majorShare }.sorted()
        if (major.isEmpty()) return words
        val out = words.toMutableList()
        ranges.forEachIndexed { n, r ->
            val v = embeddings[n] ?: return@forEachIndexed
            val cur = WordMerger.mainSpeaker(words, r)
            val best = major.maxBy { dot(centroids.getValue(it), v) }
            val curSim = centroids[cur]?.let { dot(it, v) } ?: -1f
            if (best != cur && dot(centroids.getValue(best), v) - curSim >= margin) {
                for (i in r) out[i] = out[i].copy(speaker = best)
            }
        }
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
