package com.lapcevichme.meowscribe.data.pipeline.sherpa

/**
 * Режет слишком длинные VAD-сегменты.
 *
 * На сплошном фоне (гул голосов, телевизор) Silero не видит пауз, и maxSpeechDuration не срабатывает:
 * VAD отдаёт всю запись одним сегментом. ASR на кусках длиннее ~30 с выдаёт мусор, поэтому режем сами:
 * точка разреза — самое тихое 100-мс окно во второй половине допустимой длины.
 */
internal object ChunkSplitter {

    fun split(pcm: FloatArray, range: IntRange, maxSamples: Int, sampleRate: Int): List<IntRange> {
        val win = sampleRate / 10
        val hop = sampleRate / 100
        val out = ArrayList<IntRange>()
        var s = range.first
        val e = range.last + 1
        while (e - s > maxSamples) {
            val lo = s + maxSamples / 2
            val hi = s + maxSamples - win
            // энергия скользящего окна через префиксные суммы
            val prefix = DoubleArray(hi - lo + win + 1)
            for (i in 0 until hi - lo + win) {
                val x = pcm[lo + i].toDouble()
                prefix[i + 1] = prefix[i] + x * x
            }
            var bestAt = 0
            var best = Double.MAX_VALUE
            var i = 0
            while (i < hi - lo) {
                val energy = prefix[i + win] - prefix[i]
                if (energy < best) {
                    best = energy
                    bestAt = i
                }
                i += hop
            }
            val cut = lo + bestAt + win / 2
            out += s until cut
            s = cut
        }
        out += s until e
        return out
    }
}
