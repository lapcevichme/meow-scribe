package com.lapcevichme.meowscribe.data.pipeline.sherpa

import kotlin.math.sqrt

/**
 * Второй проход диаризации поверх кластеризации sherpa.
 *
 * sherpa кластеризует эмбеддинги коротких окон, и на живом разговоре один человек часто расползается
 * на несколько «спикеров» с парой секунд речи (AMI: 9–11 вместо 4). Таких мелких спикеров отдаём
 * самому похожему по эмбеддингу всей его речи. Порог — [minSpeakerSec], но не больше 15% всей речи,
 * иначе на коротком диалоге двое собеседников сольются в одного.
 * Логика 1:1 с `recluster()` в bench/pipeline.py (там же перебор: bench/sweep_recluster.py).
 */
internal object Reclusterer {

    /**
     * @param embeddings нормированный эмбеддинг каждого спикера (у кого он посчитался)
     * @param durations суммарная длительность речи каждого спикера, с
     * @return отображение старый номер → новый номер спикера
     */
    fun relabel(embeddings: Map<Int, FloatArray>, durations: Map<Int, Float>, minSpeakerSec: Float): Map<Int, Int> {
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        embeddings.keys.sorted().forEach { groups[it] = mutableListOf(it) }

        fun dur(g: Int) = groups.getValue(g).sumOf { durations.getValue(it).toDouble() }
        fun centroid(g: Int): FloatArray {
            val v = FloatArray(embeddings.values.first().size)
            for (k in groups.getValue(g)) {
                val e = embeddings.getValue(k)
                val w = durations.getValue(k)
                for (i in v.indices) v[i] += e[i] * w
            }
            return normalize(v)
        }

        val minSec = minOf(minSpeakerSec.toDouble(), 0.15 * durations.values.sumOf { it.toDouble() })
        while (minSec > 0 && groups.size > 1) {
            val small = groups.keys.minBy { dur(it) }
            if (dur(small) >= minSec) break
            val c = centroid(small)
            val target = groups.keys.filter { it != small }.maxBy { dot(centroid(it), c) }
            groups.getValue(target) += groups.remove(small)!!
        }

        val label = durations.keys.associateWith { it }.toMutableMap()
        for ((g, ks) in groups) ks.forEach { label[it] = g }
        return label
    }

    fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat() + 1e-9f
        return FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
