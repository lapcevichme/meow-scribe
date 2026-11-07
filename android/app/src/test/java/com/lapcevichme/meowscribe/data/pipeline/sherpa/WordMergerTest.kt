package com.lapcevichme.meowscribe.data.pipeline.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class WordMergerTest {

    @Test
    fun `tokens are glued into words, standalone space and punctuation handled`() {
        val tokens = arrayOf(" При", "вет", ",", " ", "ми", "р", " !", " Как", " де", "ла", "?")
        val stamps = floatArrayOf(0.0f, 0.1f, 0.2f, 0.3f, 0.32f, 0.4f, 0.5f, 1.0f, 1.2f, 1.3f, 1.4f)
        val words = WordMerger.tokensToWords(tokens, stamps, offset = 10f, chunkEnd = 12f)
        assertEquals(listOf("Привет,", "мир!", "Как", "дела?"), words.map { it.text })
        assertEquals(10f, words[0].start, 1e-4f)
        assertEquals(10.32f, words[1].start, 1e-4f)
        // конец слова не заходит за начало следующего
        words.zipWithNext().forEach { (a, b) -> assert(a.end <= b.start) }
    }

    @Test
    fun `speakers by overlap and utterances split on speaker change`() {
        val words = listOf(
            TimedWord("Привет!", 0.0f, 0.5f),
            TimedWord("Как", 0.6f, 0.8f),
            TimedWord("дела?", 0.8f, 1.2f),
            TimedWord("Хорошо.", 1.5f, 2.0f),
            TimedWord("Мяу.", 9.0f, 9.3f), // вне сегментов → ближайший
        )
        val segs = listOf(
            SpeakerSegment(0.0f, 1.3f, 3),
            SpeakerSegment(1.4f, 2.1f, 0),
        )
        val utts = WordMerger.buildUtterances(WordMerger.assignSpeakers(words, segs), splitGap = 3f)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_2"), utts.map { it.speakerId })
        assertEquals("Привет! Как дела?", utts[0].text)
        assertEquals(0L, utts[0].startMs)
        assertEquals(1200L, utts[0].endMs)
        assertEquals("Мяу.", utts[2].text) // пауза > splitGap → новая реплика того же спикера
    }
}
