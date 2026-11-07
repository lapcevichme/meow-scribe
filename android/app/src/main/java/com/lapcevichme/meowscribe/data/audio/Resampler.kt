package com.lapcevichme.meowscribe.data.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Ресемплер на windowed-sinc (окно Блэкмана). При понижении частоты срез фильтра опускается
 * до новой частоты Найквиста, так что алиасинга нет. Ядро табулировано, поэтому sin в горячем цикле не считается.
 */
object Resampler {
    private const val ZERO_CROSSINGS = 16
    private const val TABLE_RES = 512

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = toRate.toDouble() / fromRate
        val outLen = floor(input.size * ratio).toInt()
        val out = FloatArray(outLen)

        // Срез относительно частоты Найквиста входа: при понижении берём новую частоту Найквиста с запасом.
        val cutoff = min(1.0, ratio) * 0.94
        val halfWidth = ZERO_CROSSINGS / cutoff // в отсчётах входа
        val table = buildTable()

        for (i in 0 until outLen) {
            val center = i / ratio
            val left = maxOf(0, ceil(center - halfWidth).toInt())
            val right = minOf(input.size - 1, floor(center + halfWidth).toInt())
            var acc = 0.0
            var norm = 0.0
            for (j in left..right) {
                // Позиция в единицах «пересечений нуля» фильтра.
                val x = (j - center) * cutoff
                val w = kernel(table, x)
                acc += input[j] * w
                norm += w
            }
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }

    /** table[k] = sinc(x) * blackman(x), x = k / TABLE_RES, 0 ≤ x ≤ ZERO_CROSSINGS. */
    private fun buildTable(): DoubleArray {
        val n = ZERO_CROSSINGS * TABLE_RES + 2
        return DoubleArray(n) { k ->
            val x = k.toDouble() / TABLE_RES
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val t = 0.5 + 0.5 * (x / ZERO_CROSSINGS) // 0.5..1 — правая половина окна
            val blackman = if (x >= ZERO_CROSSINGS) 0.0 else 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
            sinc * blackman
        }
    }

    private fun kernel(table: DoubleArray, x: Double): Double {
        val ax = if (x < 0) -x else x
        if (ax >= ZERO_CROSSINGS) return 0.0
        val pos = ax * TABLE_RES
        val k = pos.toInt()
        val frac = pos - k
        return table[k] + (table[k + 1] - table[k]) * frac
    }
}
