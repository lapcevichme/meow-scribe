package com.lapcevichme.meowscribe.data.pipeline.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkSplitterTest {

    private val sr = 16_000

    @Test
    fun shortRangeUntouched() {
        val pcm = FloatArray(10 * sr) { 0.1f }
        assertEquals(listOf(0 until 5 * sr), ChunkSplitter.split(pcm, 0 until 5 * sr, 20 * sr, sr))
    }

    @Test
    fun longRangeCutAtQuietSpotAndCovered() {
        // 105 с «гула», тихие места на 15 с и 32 с
        val pcm = FloatArray(105 * sr) { i -> if (i % 7 == 0) 0.3f else -0.2f }
        for (q in listOf(15 * sr, 32 * sr)) for (i in q until q + sr / 5) pcm[i] = 0f
        val parts = ChunkSplitter.split(pcm, pcm.indices, 20 * sr, sr)
        assertTrue(parts.all { it.last - it.first + 1 <= 20 * sr })
        assertEquals(0, parts.first().first)
        assertEquals(pcm.size - 1, parts.last().last)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        // первый разрез — в тихом месте на 15 с
        assertTrue(parts[0].last + 1 in 15 * sr until 15 * sr + sr / 5)
    }
}
