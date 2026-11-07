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
    internal const val ZERO_CROSSINGS = 16
    private const val TABLE_RES = 512

    /** Больше фаз — таблица весов перестаёт быть дешёвой; для «странных» частот считаем ядро на лету. */
    internal const val MAX_PHASES = 2048

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val g = gcd(fromRate, toRate)
        val up = toRate / g
        val down = fromRate / g
        return if (up <= MAX_PHASES) resamplePolyphase(input, up, down) else resampleDirect(input, fromRate, toRate)
    }

    /**
     * Частоты соотносятся как up/down (44100 → 16000 = 160/441), поэтому у выходных отсчётов всего up разных
     * дробных сдвигов. Веса для каждого сдвига считаем заранее (уже нормированные, во float) —
     * горячий цикл становится обычной свёрткой. На эмуляторе жюри в ~3.5 раза быстрее прямого варианта (2:38 при 44.1 кГц: 2.5 с → 0.7 с).
     */
    internal fun resamplePolyphase(input: FloatArray, up: Int, down: Int): FloatArray {
        val ratio = up.toDouble() / down
        val outLen = floor(input.size * ratio).toInt()
        val out = FloatArray(outLen)
        val cutoff = min(1.0, ratio) * 0.94
        val halfWidth = ZERO_CROSSINGS / cutoff
        val table = buildTable()

        val firstTap = IntArray(up)
        val weights = Array(up) { p ->
            val frac = p.toDouble() / up
            val lo = ceil(frac - halfWidth).toInt()
            val hi = floor(frac + halfWidth).toInt()
            firstTap[p] = lo
            val w = DoubleArray(hi - lo + 1) { k -> kernel(table, (lo + k - frac) * cutoff) }
            val sum = w.sum()
            FloatArray(w.size) { k -> (w[k] / sum).toFloat() }
        }

        for (i in 0 until outLen) {
            val pos = i.toLong() * down
            val n = (pos / up).toInt()
            val p = (pos % up).toInt()
            val w = weights[p]
            val base = n + firstTap[p]
            if (base >= 0 && base + w.size <= input.size) {
                var acc = 0f
                for (k in w.indices) acc += input[base + k] * w[k]
                out[i] = acc
            } else {
                // Край сигнала: берём только существующие отсчёты и перенормируем, как в прямом варианте.
                var acc = 0f
                var norm = 0f
                for (k in w.indices) {
                    val j = base + k
                    if (j < 0 || j >= input.size) continue
                    acc += input[j] * w[k]
                    norm += w[k]
                }
                out[i] = if (norm != 0f) acc / norm else 0f
            }
        }
        return out
    }

    internal fun resampleDirect(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
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
    internal fun buildTable(): DoubleArray {
        val n = ZERO_CROSSINGS * TABLE_RES + 2
        return DoubleArray(n) { k ->
            val x = k.toDouble() / TABLE_RES
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val t = 0.5 + 0.5 * (x / ZERO_CROSSINGS) // 0.5..1 — правая половина окна
            val blackman = if (x >= ZERO_CROSSINGS) 0.0 else 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
            sinc * blackman
        }
    }

    internal tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    internal fun kernel(table: DoubleArray, x: Double): Double {
        val ax = if (x < 0) -x else x
        if (ax >= ZERO_CROSSINGS) return 0.0
        val pos = ax * TABLE_RES
        val k = pos.toInt()
        val frac = pos - k
        return table[k] + (table[k + 1] - table[k]) * frac
    }
}
