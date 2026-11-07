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

    private fun w(text: String, start: Float, end: Float, speaker: Int) = TimedWord(text, start, end, speaker)

    @Test
    fun `backchannel does not steal the tail of a sentence`() {
        // «…может допустить | великую ошибку.» — на хвосте второй поддакивал, диаризация отдала хвост ему
        val words = listOf(
            w("Да,", 0.0f, 0.3f, 1), w("только", 0.3f, 0.6f, 1), w("великий", 0.6f, 1.0f, 1),
            w("человек", 1.0f, 1.4f, 1), w("может", 1.4f, 1.7f, 1), w("допустить", 1.7f, 2.2f, 1),
            w("великую", 2.2f, 2.6f, 2), w("ошибку.", 2.6f, 3.0f, 2),
            w("Да.", 3.2f, 3.5f, 2), w("И", 3.5f, 3.6f, 2), w("у", 3.6f, 3.7f, 2), w("нас", 3.7f, 4.0f, 2),
        )
        val utts = WordMerger.buildUtterances(WordMerger.smoothSpeakers(words, maxRunSec = 2f), splitGap = 3f)
        assertEquals(listOf("Да, только великий человек может допустить великую ошибку.", "Да. И у нас"), utts.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2"), utts.map { it.speakerId })
    }

    @Test
    fun `long run of another speaker inside one sentence is kept`() {
        // настоящая смена говорящего без знака препинания: оба куска длинные
        val words = listOf(
            w("я", 0.0f, 0.5f, 1), w("думаю", 0.5f, 1.2f, 1), w("что", 1.2f, 1.6f, 1), w("эфир", 1.6f, 2.4f, 1),
            w("нет", 2.5f, 3.2f, 2), w("эфира", 3.2f, 4.0f, 2), w("не", 4.0f, 4.4f, 2), w("было", 4.4f, 5.0f, 2),
        )
        val smoothed = WordMerger.smoothSpeakers(words, maxRunSec = 2f)
        assertEquals(words.map { it.speaker }, smoothed.map { it.speaker })
    }

    @Test
    fun `dash and pause split sentences, so a short reply stays with its speaker`() {
        val words = listOf(
            w("Облака?—", 0.0f, 0.6f, 1),
            w("Нет.—", 0.62f, 0.9f, 2), // короткий ответ, но отдельное предложение
            w("Не", 0.92f, 1.0f, 1), w("совсем", 1.0f, 1.5f, 1), w("так", 1.5f, 2.0f, 1),
            w("угу", 3.5f, 3.7f, 2), // пауза > 1 с — отдельное «предложение»
        )
        val smoothed = WordMerger.smoothSpeakers(words, maxRunSec = 2f)
        assertEquals(listOf(1, 2, 1, 1, 1, 2), smoothed.map { it.speaker })
        assertEquals(words.map { it.speaker }, WordMerger.smoothSpeakers(words, maxRunSec = 0f).map { it.speaker })
    }

    @Test
    fun `backchannel words are recognized strictly`() {
        val yes = listOf("Да.", "да,", "Угу.", "Да-да-да-да-да-да-да.", "угу-угу", "Yeah.", "Mm-hmm.", "Конечно,", "Ага!", "Okay,")
        val no = listOf("да", "Ну,", "Так", "вот.", "а", "Данные.", "ошибку.", "okay", "—", "")
        yes.forEach { assert(WordMerger.isBackchannel(it)) { it } }
        no.forEach { assert(!WordMerger.isBackchannel(it)) { it } }
    }

    @Test
    fun `backchannel under two speakers goes to the one with the shorter segment`() {
        val segs = listOf(SpeakerSegment(0f, 10f, 1), SpeakerSegment(4.0f, 4.6f, 2))
        val words = listOf(
            w("поехали", 3.4f, 4.0f, -1),
            w("Угу.", 4.1f, 4.5f, -1),   // накрыт обоими → короткому сегменту
            w("да", 4.1f, 4.5f, -1),     // без знака — обычное слово, не трогаем
            w("Ага.", 8.0f, 8.3f, -1),   // накрыт одним спикером
        )
        val out = WordMerger.assignBackchannels(WordMerger.assignSpeakers(words, segs), segs)
        assertEquals(listOf(1, 2, 1, 1), out.map { it.speaker })
    }

    @Test
    fun `backchannel becomes its own overlapping utterance and does not split the main one`() {
        val words = listOf(
            w("и", 0.0f, 0.2f, 1), w("тогда", 0.2f, 0.6f, 1), w("мы", 0.6f, 0.8f, 1), w("поехали", 0.8f, 1.4f, 1),
            w("Да-да.", 1.4f, 1.8f, 2),
            w("в", 1.8f, 1.9f, 1), w("лабораторию", 1.9f, 2.8f, 1), w("смотреть.", 2.8f, 3.4f, 1),
            w("Так.", 3.6f, 3.9f, 2), // отдельное предложение — обычная реплика
        )
        val (main, extra) = WordMerger.extractBackchannels(words, maxSec = 1.5f)
        assertEquals(listOf(listOf("Да-да.")), extra.map { r -> r.map { it.text } })
        val utts = WordMerger.buildUtterances(main, splitGap = 3f, sentenceGap = 0.4f, extra = extra)
        assertEquals(
            listOf("и тогда мы поехали в лабораторию смотреть.", "Да-да.", "Так."),
            utts.map { it.text },
        )
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_2"), utts.map { it.speakerId })
        assertEquals(1400L, utts[1].startMs)
        assertEquals(words, WordMerger.extractBackchannels(words, maxSec = 0f).first)
    }

    @Test
    fun `long content run of another speaker is not extracted as backchannel`() {
        val words = listOf(
            w("мы", 0.0f, 0.4f, 1), w("думали", 0.4f, 1.0f, 1),
            w("Да,", 1.0f, 1.3f, 2), w("эфир", 1.3f, 1.8f, 2), // «Да,» + содержательное слово — не поддакивание
            w("существует.", 1.8f, 2.6f, 1),
        )
        assertEquals(0, WordMerger.extractBackchannels(words, maxSec = 1.5f).second.size)
    }

    @Test
    fun `sentence goes to a major speaker whose voice is clearly closer`() {
        val words = listOf(
            w("Здравствуйте!", 0f, 1.6f, 1),
            w("Слушай,", 2f, 2.5f, 1), w("расскажи", 2.5f, 3.2f, 1), w("нам?", 3.2f, 3.8f, 1),
            w("Конечно.", 4f, 5.6f, 1),
        )
        val ranges = SentenceRelabeler.candidates(words, minSec = 1.5f)
        assertEquals(listOf(0..0, 1..3, 4..4), ranges)
        val centroids = mapOf(1 to floatArrayOf(1f, 0f, 0f), 2 to floatArrayOf(0f, 1f, 0f), 3 to floatArrayOf(0f, 0f, 1f))
        val durations = mapOf(1 to 100f, 2 to 50f, 3 to 5f) // 3 — мелкий обрывок кластеризации
        val embeddings = listOf(
            floatArrayOf(0.6f, 0.8f, 0f),   // ближе к 2 с перевесом 0.2 → переходит
            floatArrayOf(0.7f, 0.714f, 0f), // перевес 0.014 < margin → остаётся
            floatArrayOf(0.1f, 0f, 0.995f), // ближе всего к мелкому 3 — ему не отдаём
        )
        val out = SentenceRelabeler.relabel(words, ranges, embeddings, centroids, durations, margin = 0.1f, majorShare = 0.1f)
        assertEquals(listOf(2, 1, 1, 1, 1), out.map { it.speaker })
    }
}
