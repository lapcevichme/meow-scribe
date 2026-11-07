package com.lapcevichme.meowscribe.data.pipeline.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class ReclustererTest {

    private fun e(vararg x: Float) = Reclusterer.normalize(x)

    @Test
    fun smallSpeakerGoesToMostSimilar() {
        val emb = mapOf(0 to e(1f, 0f), 1 to e(0f, 1f), 2 to e(0.1f, 1f))
        val dur = mapOf(0 to 40f, 1 to 30f, 2 to 3f)
        val label = Reclusterer.relabel(emb, dur, minSpeakerSec = 6f)
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 1), label)
    }

    @Test
    fun shortRecordingKeepsBothSpeakers() {
        // 2 собеседника по 4–5 с: порог ограничен 15% речи (1.35 с), никто не склеивается
        val emb = mapOf(0 to e(1f, 0f), 1 to e(0.6f, 0.8f))
        val dur = mapOf(0 to 5f, 1 to 4f)
        assertEquals(mapOf(0 to 0, 1 to 1), Reclusterer.relabel(emb, dur, minSpeakerSec = 6f))
    }
}
