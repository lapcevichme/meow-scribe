package com.lapcevichme.meowscribe.data.pipeline.sherpa

import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.model.Word

/** Слово с привязкой к спикеру диаризации. Время в секундах. */
internal data class TimedWord(
    val text: String,
    val start: Float,
    val end: Float,
    val speaker: Int = -1,
)

internal data class SpeakerSegment(val start: Float, val end: Float, val speaker: Int)

/**
 * Склейка ASR и диаризации. Логика 1:1 с bench/pipeline.py (tokens_to_words, assign_speakers,
 * build_utterances) — менять синхронно.
 */
internal object WordMerger {

    /**
     * BPE-токены → слова. Новый токен начинает слово, если начинается с пробела или «▁».
     * Отдельно стоящая пунктуация приклеивается к предыдущему слову.
     * @param offset начало чанка в секундах, @param chunkEnd конец чанка в секундах.
     */
    fun tokensToWords(tokens: Array<String>, stamps: FloatArray, offset: Float, chunkEnd: Float): List<TimedWord> {
        val texts = ArrayList<StringBuilder>()
        val starts = ArrayList<Float>()
        val lasts = ArrayList<Float>()
        val n = minOf(tokens.size, stamps.size)
        var pending = false // был отдельный токен-пробел → следующий токен начинает слово
        for (i in 0 until n) {
            val tok = tokens[i]
            val t = offset + stamps[i]
            val boundary = tok.startsWith(" ") || tok.startsWith("▁") || pending || texts.isEmpty()
            if (boundary) {
                val piece = tok.trimStart(' ', '▁')
                if (piece.isEmpty()) {
                    pending = true
                    continue
                }
                pending = false
                if (texts.isNotEmpty() && piece.none { it.isLetterOrDigit() }) {
                    texts.last().append(piece)
                    lasts[lasts.lastIndex] = t
                    continue
                }
                texts += StringBuilder(piece)
                starts += t
                lasts += t
            } else {
                texts.last().append(tok)
                lasts[lasts.lastIndex] = t
            }
        }
        return texts.indices.map { i ->
            val next = if (i + 1 < texts.size) starts[i + 1] else chunkEnd
            val end = maxOf(starts[i] + 0.08f, minOf(next - 0.02f, lasts[i] + 0.25f))
            TimedWord(texts[i].toString(), starts[i], end)
        }
    }

    /** Спикер слова = сегмент с максимальным перекрытием; если перекрытия нет — ближайший сегмент. */
    fun assignSpeakers(words: List<TimedWord>, segments: List<SpeakerSegment>): List<TimedWord> {
        if (segments.isEmpty()) return words.map { it.copy(speaker = 0) }
        return words.map { w ->
            var best = -1
            var bestOverlap = 0f
            for (s in segments) {
                val o = minOf(w.end, s.end) - maxOf(w.start, s.start)
                if (o > bestOverlap) {
                    bestOverlap = o
                    best = s.speaker
                }
            }
            if (best < 0) {
                val mid = (w.start + w.end) / 2
                best = segments.minBy { s ->
                    if (mid in s.start..s.end) 0f else minOf(kotlin.math.abs(mid - s.start), kotlin.math.abs(mid - s.end))
                }.speaker
            }
            w.copy(speaker = best)
        }
    }

    /**
     * Новая реплика — при смене спикера, паузе дольше [splitGap] с или паузе дольше [sentenceGap] с
     * после конца предложения. Спикеры нумеруются по порядку появления.
     */
    fun buildUtterances(words: List<TimedWord>, splitGap: Float, sentenceGap: Float = Float.MAX_VALUE): List<Utterance> {
        val groups = ArrayList<MutableList<TimedWord>>()
        for (w in words) {
            val cur = groups.lastOrNull()
            val gap = if (cur == null) 0f else w.start - cur.last().end
            val sentenceEnd = cur != null && cur.last().text.trimEnd('»', '"', '\'', ')').let {
                it.endsWith('.') || it.endsWith('!') || it.endsWith('?') || it.endsWith('…')
            }
            if (cur == null || w.speaker != cur.last().speaker || gap > splitGap || (sentenceEnd && gap > sentenceGap)) {
                groups += mutableListOf(w)
            } else {
                cur += w
            }
        }
        val names = HashMap<Int, String>()
        return groups.map { g ->
            val id = names.getOrPut(g.first().speaker) { "speaker_${names.size + 1}" }
            Utterance(
                speakerId = id,
                startMs = (g.first().start * 1000).toLong(),
                endMs = (g.last().end * 1000).toLong(),
                text = g.joinToString(" ") { it.text },
                words = g.map { Word(it.text, (it.start * 1000).toLong(), (it.end * 1000).toLong()) },
            )
        }
    }
}
