package com.lapcevichme.meowscribe.domain.voice

import kotlin.math.sqrt

object VoiceMath {

    /**
     * Порог косинусной близости для эмбеддингов CAM++ (3D-Speaker). В примерах sherpa-onnx
     * для идентификации берут 0.5–0.6; выше — меньше ложных «узнаваний», но чаще не узнаём.
     */
    const val MATCH_THRESHOLD = 0.6f

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat()
        return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
    }

    /** Косинус для уже нормированных векторов. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /** Новый центроид профиля: среднее [samples] прежних векторов и [added], снова нормированное. */
    fun mergeCentroid(centroid: FloatArray, samples: Int, added: FloatArray): FloatArray {
        if (centroid.size != added.size) return normalize(added)
        val n = samples.coerceAtLeast(1)
        return normalize(FloatArray(centroid.size) { (centroid[it] * n + added[it]) / (n + 1) })
    }

    /**
     * Сопоставляет спикеров записи с сохранёнными голосами. Жадно по убыванию близости:
     * один профиль — максимум один спикер записи, и наоборот (иначе двое разных людей
     * могли бы стать «Михаилом» только потому, что оба чуть похожи на него).
     */
    fun match(
        speakers: Map<String, FloatArray>,
        profiles: List<StoredVoice>,
        threshold: Float = MATCH_THRESHOLD,
    ): List<VoiceMatch> {
        val pairs = buildList {
            for ((speakerId, v) in speakers) for (p in profiles) {
                val sim = cosine(v, p.embedding)
                if (sim >= threshold) add(Triple(speakerId, p, sim))
            }
        }.sortedByDescending { it.third }

        val usedSpeakers = HashSet<String>()
        val usedProfiles = HashSet<Long>()
        return pairs.mapNotNull { (speakerId, p, sim) ->
            if (speakerId in usedSpeakers || p.id in usedProfiles) return@mapNotNull null
            usedSpeakers += speakerId
            usedProfiles += p.id
            VoiceMatch(speakerId, p.id, p.name, sim)
        }
    }
}
