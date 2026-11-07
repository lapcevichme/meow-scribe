package com.lapcevichme.meowscribe

import com.lapcevichme.meowscribe.domain.export.TranscriptFormatter
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.model.Word
import com.lapcevichme.meowscribe.domain.usecase.MergeUtterancesUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFormatterTest {

    private val dialog = listOf(
        Utterance("speaker_1", 0, 1_500, "Добрый день!"),
        Utterance("speaker_1", 1_600, 3_000, "Вы не видели мою миску?"),
        Utterance("speaker_2", 3_200, 4_000, "Мяу."),
    )

    @Test
    fun txtGroupsConsecutiveSpeakerLines() {
        val txt = TranscriptFormatter.txt(listOf("Кухня"), dialog, mapOf("speaker_1" to "Анна"), useNames = true, timestamps = true)
        assertEquals(
            "Кухня\n\n[00:00] Анна\nДобрый день!\nВы не видели мою миску?\n\n[00:03] Спикер 2\nМяу.\n",
            txt,
        )
    }

    @Test
    fun txtWithoutNamesUsesSpeakerIds() {
        val txt = TranscriptFormatter.txt(emptyList(), dialog, mapOf("speaker_1" to "Анна"), useNames = false, timestamps = false)
        assertTrue(txt.startsWith("speaker_1\nДобрый день!"))
    }

    @Test
    fun srtHasStandardTimecodes() {
        val srt = TranscriptFormatter.srt(TranscriptFormatter.srtCues(dialog.take(1), emptyMap(), useNames = true))
        assertEquals("1\n00:00:00,000 --> 00:00:01,500\nСпикер 1: Добрый день!\n\n", srt)
    }

    @Test
    fun longUtteranceIsSplitIntoShortCuesUsingWordTimes() {
        val words = (0 until 30).map { Word("слово$it", it * 500L, it * 500L + 400) }
        val u = Utterance("speaker_1", 0, 15_000, words.joinToString(" ") { it.text }, words)
        val cues = TranscriptFormatter.srtCues(listOf(u), emptyMap(), useNames = false)
        assertTrue(cues.size > 1)
        cues.forEach { c ->
            assertTrue("длинная строка: ${c.text}", c.text.lines().all { it.length <= TranscriptFormatter.SRT_LINE + 12 })
            assertTrue(c.endMs - c.startMs <= TranscriptFormatter.SRT_MAX_MS)
        }
        assertEquals(0L, cues.first().startMs)
        assertEquals(15_000L, cues.last().endMs)
        // Склейка кусков даёт исходный текст: слова не теряются.
        assertEquals(u.text, cues.joinToString(" ") { it.text.replace("\n", " ") }.removePrefix("speaker_1: "))
    }

    @Test
    fun slowShortPhraseStaysInOneCue() {
        val u = Utterance("speaker_1", 0, 22_000, "Добрый день! Вы не видели мою миску?")
        assertEquals(1, TranscriptFormatter.srtCues(listOf(u), emptyMap(), useNames = true).size)
    }

    @Test
    fun cutsPreferPunctuation() {
        val text = "Первая часть длинной фразы про миску и кота, вторая часть длинной фразы про диван и рыбок, третья часть длинной фразы."
        val cues = TranscriptFormatter.srtCues(listOf(Utterance("speaker_1", 0, 6_000, text)), emptyMap(), useNames = false)
        assertTrue(cues.dropLast(1).all { it.text.replace("\n", " ").endsWith(",") })
    }

    @Test
    fun mergeKeepsWordsAndSpansBothUtterances() {
        val merged = MergeUtterancesUseCase.merge(dialog[0], dialog[1], "speaker_1")
        assertEquals("Добрый день! Вы не видели мою миску?", merged.text)
        assertEquals(0L, merged.startMs)
        assertEquals(3_000L, merged.endMs)
    }
}
