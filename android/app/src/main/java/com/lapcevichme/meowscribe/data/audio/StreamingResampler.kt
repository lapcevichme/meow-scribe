package com.lapcevichme.meowscribe.data.audio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Потоковый вариант [Resampler.resample]: вход приходит кусками прямо из декодера, на выходе те же
 * отсчёты, что и при обработке всего сигнала разом (те же веса, тот же порядок суммирования, те же края).
 * В памяти держим только хвост входа шириной в фильтр, а не всю запись на исходной частоте:
 * полтора часа при 44.1 кГц — это ~1 ГБ float, в Java heap (512 МБ) не помещается.
 *
 * @param sink получает готовые отсчёты; массив переиспользуется, поэтому их нужно сразу скопировать.
 */
class StreamingResampler(
    fromRate: Int,
    toRate: Int,
    private val sink: (FloatArray, Int) -> Unit,
) {
    private val passthrough = fromRate == toRate
    private val up: Int
    private val down: Int
    private val polyphase: Boolean
    private val ratio: Double
    private val cutoff: Double
    private val halfWidth: Double
    private val table: DoubleArray
    private val firstTap: IntArray
    private val weights: Array<FloatArray>
    private val minFirstTap: Int

    init {
        val g = Resampler.gcd(fromRate, toRate)
        up = toRate / g
        down = fromRate / g
        polyphase = up <= Resampler.MAX_PHASES
        ratio = if (polyphase) up.toDouble() / down else toRate.toDouble() / fromRate
        cutoff = min(1.0, ratio) * 0.94
        halfWidth = Resampler.ZERO_CROSSINGS / cutoff
        table = if (passthrough) DoubleArray(0) else Resampler.buildTable()
        if (polyphase && !passthrough) {
            firstTap = IntArray(up)
            weights = Array(up) { p ->
                val frac = p.toDouble() / up
                val lo = ceil(frac - halfWidth).toInt()
                val hi = floor(frac + halfWidth).toInt()
                firstTap[p] = lo
                val w = DoubleArray(hi - lo + 1) { k -> Resampler.kernel(table, (lo + k - frac) * cutoff) }
                val sum = w.sum()
                FloatArray(w.size) { k -> (w[k] / sum).toFloat() }
            }
            minFirstTap = firstTap.min()
        } else {
            firstTap = IntArray(0)
            weights = emptyArray()
            minFirstTap = 0
        }
    }

    // Окно входа: buf[0] — это отсчёт с абсолютным номером bufStart.
    private var buf = FloatArray(1 shl 14)
    private var bufStart = 0L
    private var bufLen = 0
    private val inCount get() = bufStart + bufLen

    private var outIdx = 0L
    private val out = FloatArray(1 shl 13)
    private var outN = 0

    fun push(src: FloatArray, n: Int) {
        if (n <= 0) return
        if (passthrough) {
            sink(src, n)
            return
        }
        if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        System.arraycopy(src, 0, buf, bufLen, n)
        bufLen += n
        produce(final = false)
        compact()
    }

    /** Досчитать хвост: у последних отсчётов фильтр выходит за конец сигнала, края перенормируются. */
    fun finish() {
        if (passthrough) return
        produce(final = true)
        if (outN > 0) {
            sink(out, outN)
            outN = 0
        }
    }

    private fun produce(final: Boolean) {
        val total = inCount
        val outTotal = if (final) floor(total * ratio).toLong() else Long.MAX_VALUE
        while (outIdx < outTotal) {
            val v = if (polyphase) {
                val pos = outIdx * down
                val n = pos / up
                val p = (pos % up).toInt()
                val w = weights[p]
                val base = n + firstTap[p]
                if (!final && base + w.size > total) break
                if (base >= 0 && base + w.size <= total) {
                    val off = (base - bufStart).toInt()
                    var acc = 0f
                    for (k in w.indices) acc += buf[off + k] * w[k]
                    acc
                } else {
                    var acc = 0f
                    var norm = 0f
                    for (k in w.indices) {
                        val j = base + k
                        if (j < 0 || j >= total) continue
                        acc += buf[(j - bufStart).toInt()] * w[k]
                        norm += w[k]
                    }
                    if (norm != 0f) acc / norm else 0f
                }
            } else {
                val center = outIdx / ratio
                val rightRaw = floor(center + halfWidth).toLong()
                if (!final && rightRaw >= total) break
                val left = maxOf(0L, ceil(center - halfWidth).toLong())
                val right = minOf(total - 1, rightRaw)
                var acc = 0.0
                var norm = 0.0
                for (j in left..right) {
                    val w = Resampler.kernel(table, (j - center) * cutoff)
                    acc += buf[(j - bufStart).toInt()] * w
                    norm += w
                }
                if (norm != 0.0) (acc / norm).toFloat() else 0f
            }
            out[outN++] = v
            if (outN == out.size) {
                sink(out, outN)
                outN = 0
            }
            outIdx++
        }
    }

    /** Выкидываем вход левее самого левого отсчёта, нужного следующему выходу. */
    private fun compact() {
        val keepFrom = if (polyphase) {
            outIdx * down / up + minFirstTap
        } else {
            ceil(outIdx / ratio - halfWidth).toLong() - 1
        }.coerceIn(bufStart, inCount)
        val drop = (keepFrom - bufStart).toInt()
        if (drop < buf.size / 2) return
        System.arraycopy(buf, drop, buf, 0, bufLen - drop)
        bufLen -= drop
        bufStart = keepFrom
    }
}
