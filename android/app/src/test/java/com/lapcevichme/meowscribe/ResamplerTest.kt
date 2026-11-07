package com.lapcevichme.meowscribe

import com.lapcevichme.meowscribe.data.audio.Resampler
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class ResamplerTest {

    private fun signal(n: Int, rate: Int): FloatArray {
        val rnd = Random(42)
        return FloatArray(n) { i -> (0.5 * sin(2 * Math.PI * 440 * i / rate) + 0.1 * (rnd.nextFloat() - 0.5)).toFloat() }
    }

    private fun assertSameAsDirect(from: Int, to: Int) {
        val input = signal(from * 2 + 123, from)
        val g = generateSequence(from to to) { (a, b) -> b to a % b }.first { it.second == 0 }.first
        val fast = Resampler.resamplePolyphase(input, to / g, from / g)
        val direct = Resampler.resampleDirect(input, from, to)
        assertEquals(direct.size, fast.size)
        val maxDiff = direct.indices.maxOf { abs(direct[it] - fast[it]) }
        assert(maxDiff < 1e-4f) { "$from→$to: расхождение $maxDiff" }
    }

    @Test fun downsample44100() = assertSameAsDirect(44100, 16000)
    @Test fun downsample48000() = assertSameAsDirect(48000, 16000)
    @Test fun downsample22050() = assertSameAsDirect(22050, 16000)
    @Test fun upsample8000() = assertSameAsDirect(8000, 16000)
}
