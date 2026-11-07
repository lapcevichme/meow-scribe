package com.lapcevichme.meowscribe

import com.lapcevichme.meowscribe.domain.voice.StoredVoice
import com.lapcevichme.meowscribe.domain.voice.VoiceMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMathTest {

    private fun v(vararg x: Float) = VoiceMath.normalize(x)

    @Test
    fun matchesEachProfileAtMostOnce() {
        val profiles = listOf(StoredVoice(1, "Миша", 1, v(1f, 0f, 0f)), StoredVoice(2, "Аня", 1, v(0f, 1f, 0f)))
        val speakers = mapOf(
            "speaker_1" to v(0.9f, 0.1f, 0f),   // очень похож на Мишу
            "speaker_2" to v(0.8f, 0.2f, 0.1f), // тоже похож на Мишу, но меньше — Мишей не станет
            "speaker_3" to v(0.1f, 1f, 0f),     // Аня
        )
        val result = VoiceMath.match(speakers, profiles).associate { it.speakerId to it.name }
        assertEquals(mapOf("speaker_1" to "Миша", "speaker_3" to "Аня"), result)
    }

    @Test
    fun belowThresholdIsNotMatched() {
        val profiles = listOf(StoredVoice(1, "Миша", 1, v(1f, 0f)))
        assertTrue(VoiceMath.match(mapOf("speaker_1" to v(0.5f, 1f)), profiles).isEmpty())
    }

    @Test
    fun centroidMovesTowardsNewSample() {
        val merged = VoiceMath.mergeCentroid(v(1f, 0f), samples = 1, added = v(0f, 1f))
        assertEquals(VoiceMath.cosine(merged, v(1f, 0f)), VoiceMath.cosine(merged, v(0f, 1f)), 1e-5f)
        assertEquals(1f, VoiceMath.cosine(merged, merged), 1e-5f)
    }
}
