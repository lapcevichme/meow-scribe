package com.lapcevichme.meowscribe.data.pipeline.sherpa

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Переоценка спикера целых предложений по голосу.
 *
 * На быстром диалоге диаризация не ставит границу на смене говорящего (даже при паузе в секунду) и
 * склеивает реплики разных людей в один сегмент, а иногда режет реплику посреди фразы. Пунктуация ASR
 * даёт границы предложений, а предложение почти всегда говорит один человек. Поэтому:
 *  1. считаем эмбеддинг CAM++ каждого предложения от `sentenceMinSec` с;
 *  2. длинные предложения (от `clusterMinSec` с) крупных спикеров кластеризуем ([cluster], полная связь)
 *     на столько кластеров, сколько у диаризации крупных спикеров, и берём центроиды кластеров — они чище
 *     центроидов по сегментам диаризации, где длинный сегмент часто склеен из двух людей. Если кластеры
 *     не сходятся с диаризацией по объёму — центроиды по сегментам;
 *  3. предложение переходит к другому крупному спикеру, если его центроид ближе с перевесом ≥ margin;
 *     мелкий спикер без центроида не трогается, если только его голос не похож на основного (обрывок).
 *
 * Стенд (DER / cpWER, реплики целиком), относительно сверки по центроидам сегментов: ru 9.8 → 9.5%,
 * ru_dev 12.0 → 10.8%, ru_multi 8.0 → 5.6%, babble 17.8 → 13.6%, far 34.4 → 29.3%, AMI 32.2 → 30.2% /
 * 44.6 → 40.0%; хуже нигде. Подробно — bench/README.md.
 * Логика 1:1 с relabel_sentences() в bench/pipeline.py.
 */
internal object SentenceRelabeler {

    /** Предложение с эмбеддингом голоса. */
    class Unit(val range: IntRange, val embedding: FloatArray, val seconds: Float, val speaker: Int)

    /** Предложения, по которым стоит считать эмбеддинг: от [minSec] с. */
    fun candidates(words: List<TimedWord>, minSec: Float): List<IntRange> =
        WordMerger.sentenceRanges(words).filter { words[it.last].end - words[it.first].start >= minSec }

    /**
     * Центроиды спикеров по кластерам длинных предложений. Кластеров столько, сколько крупных спикеров
     * (доля речи ≥ [majorShare]); имена кластеров — метки диаризации, жадно по наибольшей общей длительности.
     * @param minBalance кластер должен набрать хотя бы такую долю от длительности предложений с его меткой
     * @return null — кластеризовать нечего (меньше двух крупных спикеров или мало предложений)
     *   или кластеры не сходятся с диаризацией по объёму
     */
    fun clusterCentroids(
        units: List<Unit>,
        durations: Map<Int, Float>,
        majorShare: Float,
        clusterMinSec: Float,
        maxUnits: Int,
        minBalance: Float,
    ): Map<Int, FloatArray>? {
        val total = durations.values.sum().takeIf { it > 0f } ?: return null
        val major = durations.filter { it.value / total >= majorShare }.keys.sorted()
        // предложения мелких спикеров в кластеризацию не берём: их голос размывает кластеры основных
        val pool = units.filter { it.seconds >= clusterMinSec && it.speaker in major }
            .sortedByDescending { it.seconds }.take(maxUnits)
        if (major.size < 2 || pool.size <= major.size) return null
        val rep = cluster(pool.map { it.embedding }, major.size)

        val keys = (pool.map { it.speaker } + major).toSortedSet().toList()
        val reps = rep.toSortedSet().toList()
        val overlap = HashMap<Pair<Int, Int>, Float>()
        pool.forEachIndexed { i, u -> overlap.merge(rep[i] to u.speaker, u.seconds, Float::plus) }
        val pairs = reps.flatMap { r -> keys.map { k -> Triple(overlap[r to k] ?: 0f, r, k) } }
            .sortedWith(compareBy<Triple<Float, Int, Int>>({ -it.first }, { it.second }, { it.third }))
        val name = HashMap<Int, Int>()
        val used = HashSet<Int>()
        for ((_, r, k) in pairs) {
            if (r !in name && k !in used) {
                name[r] = k
                used += k
            }
        }
        // Кластеры не совпали с диаризацией по объёму: обычно это третий голос, который кластеризация
        // выделила вместо одного из основных (а два основных слила). Такие центроиды хуже сегментных.
        val clusterSec = HashMap<Int, Float>()
        val labelSec = HashMap<Int, Float>()
        pool.forEachIndexed { i, u ->
            clusterSec.merge(name.getValue(rep[i]), u.seconds, Float::plus)
            labelSec.merge(u.speaker, u.seconds, Float::plus)
        }
        val balance = clusterSec.mapNotNull { (k, s) -> labelSec[k]?.let { s / it } }.minOrNull() ?: 1f
        if (balance < minBalance) return null

        val acc = HashMap<Int, FloatArray>()
        pool.forEachIndexed { i, u ->
            val v = acc.getOrPut(name.getValue(rep[i])) { FloatArray(u.embedding.size) }
            for (d in v.indices) v[d] += u.embedding[d] * u.seconds
        }
        return acc.mapValues { normalize(it.value) }
    }

    /**
     * Иерархическая кластеризация: полная связь, косинусное расстояние, до [k] кластеров.
     * Векторы нормированы. Возвращает представителя кластера для каждого вектора.
     */
    fun cluster(x: List<FloatArray>, k: Int): IntArray {
        val n = x.size
        val d = Array(n) { i -> DoubleArray(n) { j -> if (i == j) Double.POSITIVE_INFINITY else 1.0 - dot(x[i], x[j]) } }
        val rep = IntArray(n) { it }
        val active = BooleanArray(n) { true }
        repeat(max(0, n - k)) {
            var best = Double.POSITIVE_INFINITY
            var bi = -1
            var bj = -1
            for (i in 0 until n) {
                if (!active[i]) continue
                for (j in i + 1 until n) {
                    if (active[j] && d[i][j] < best) {
                        best = d[i][j]
                        bi = i
                        bj = j
                    }
                }
            }
            if (bi < 0) return rep
            for (t in 0 until n) {
                val m = max(d[bi][t], d[bj][t])
                d[bi][t] = m
                d[t][bi] = m
            }
            d[bi][bi] = Double.POSITIVE_INFINITY
            active[bj] = false
            for (t in rep.indices) if (rep[t] == bj) rep[t] = bi
        }
        return rep
    }

    /**
     * @param centroids нормированный эмбеддинг каждого спикера
     * @param durations суммарная речь каждого спикера по сегментам диаризации, с
     * @param fragmentSim мелкий спикер без центроида с голосом ближе этого к основному считается обрывком
     */
    fun relabel(
        words: List<TimedWord>,
        units: List<Unit>,
        centroids: Map<Int, FloatArray>,
        durations: Map<Int, Float>,
        margin: Float,
        majorShare: Float,
        fragmentSim: Float,
    ): List<TimedWord> {
        if (centroids.size < 2) return words
        val total = durations.values.sum().takeIf { it > 0f } ?: return words
        val targets = centroids.keys.filter { (durations[it] ?: 0f) / total >= majorShare }.sorted()
            .ifEmpty { centroids.keys.sorted() }
        val out = words.toMutableList()
        // Мелкий спикер без центроида кластера: если его голос похож на кого-то из основных, это обрывок
        // (диаризация отрезала кусок человека) — его предложения переносим; иначе живой человек — не трогаем.
        val voice = HashMap<Int, FloatArray>()
        for (u in units) {
            if (u.speaker in centroids) continue
            val v = voice.getOrPut(u.speaker) { FloatArray(u.embedding.size) }
            for (d in v.indices) v[d] += u.embedding[d] * u.seconds
        }
        val fragments = voice.filterValues { v -> val n = normalize(v); centroids.values.maxOf { dot(it, n) } >= fragmentSim }.keys
        for (u in units) {
            val curSim = centroids[u.speaker]?.let { dot(it, u.embedding) }
                ?: if (u.speaker in fragments) -1f else continue
            val best = targets.maxBy { dot(centroids.getValue(it), u.embedding) }
            if (best != u.speaker && dot(centroids.getValue(best), u.embedding) - curSim >= margin) {
                for (i in u.range) out[i] = out[i].copy(speaker = best)
            }
        }
        return out
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
