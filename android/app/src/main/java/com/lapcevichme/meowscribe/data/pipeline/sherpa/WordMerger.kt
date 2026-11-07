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
 * assign_backchannels, smooth_speakers, extract_backchannels, build_utterances) — менять синхронно.
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

    // Строгие реакции-поддакивания. «ну», «вот», «так», «а» сюда не входят: в русском это обычные
    // служебные слова посреди фразы, и на подкасте их перенос рвал реплики.
    private val BACKCHANNELS = setOf(
        "да", "ага", "угу", "мгм", "ясно", "понятно", "конечно", "ок", "окей", "хорошо", "точно", "верно",
        "именно", "согласен", "согласна", "ого", "вау", "ой", "хм", "мм", "ммм",
        "yeah", "yes", "yep", "yup", "mhm", "mmhmm", "mmhm", "uhhuh", "okay", "ok", "right", "sure", "exactly",
        "oh", "wow", "hmm", "mmm", "alright", "true", "cool",
    )
    private val REPEATED = listOf("да", "ага", "угу", "yeah", "mm", "okay")

    /**
     * Слово — реакция слушателя: повтор междометия («да-да-да», «угу-угу») или междометие из списка со
     * знаком препинания после («Да.», «угу,», «Yeah.»). Голое «да» посреди фразы — обычное слово.
     */
    fun isBackchannel(text: String): Boolean {
        val key = text.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }
        if (key.isEmpty()) return false
        if (REPEATED.any { b -> key.length >= 2 * b.length && key.length % b.length == 0 && key == b.repeat(key.length / b.length) }) {
            return true
        }
        return key in BACKCHANNELS && text.trimEnd('»', '"', '\'', ')').let {
            it.endsWith('.') || it.endsWith('!') || it.endsWith('?') || it.endsWith('…') || it.endsWith(',')
        }
    }

    /**
     * Поддакивание поверх чужой речи диаризация часто видит (сегменты двух спикеров перекрываются), но по
     * максимальному перекрытию слово достаётся тому, кто говорит дольше. Если реакцию ([isBackchannel])
     * хотя бы наполовину накрывают сегменты двух разных спикеров, отдаём её спикеру с самым коротким
     * сегментом: перебивающий говорит коротко. Вызывать после [assignSpeakers].
     */
    fun assignBackchannels(words: List<TimedWord>, segments: List<SpeakerSegment>): List<TimedWord> = words.map { w ->
        if (!isBackchannel(w.text)) return@map w
        val half = maxOf(w.end - w.start, 1e-3f) * 0.5f
        val cover = segments.filter { minOf(w.end, it.end) - maxOf(w.start, it.start) >= half }
        if (cover.distinctBy { it.speaker }.size < 2) w else w.copy(speaker = cover.minBy { it.end - it.start }.speaker)
    }

    /** Предложения: конец на .!?… или «—», следующее слово начинается с «—», пауза дольше [pauseGap] с. */
    fun sentenceRanges(words: List<TimedWord>, pauseGap: Float = 1f): List<IntRange> {
        val out = ArrayList<IntRange>()
        var from = 0
        for (i in words.indices) {
            if (i == words.lastIndex || endsSentence(words[i].text) || words[i + 1].text.startsWith('—') ||
                words[i + 1].start - words[i].end > pauseGap
            ) {
                out += from..i
                from = i + 1
            }
        }
        return out
    }

    /** Спикер, который в [range] говорит дольше всех (при равенстве — кто раньше начал). */
    fun mainSpeaker(words: List<TimedWord>, range: IntRange): Int {
        val talk = LinkedHashMap<Int, Float>()
        for (i in range) talk.merge(words[i].speaker, words[i].end - words[i].start, Float::plus)
        return talk.maxBy { it.value }.key
    }

    /**
     * Поддакивания — отдельными репликами. Кусок другого спикера внутри предложения из 1–2 реакций
     * ([isBackchannel]) короче [maxSec] с вынимаем из потока слов: основная реплика не рвётся, а «угу»
     * становится своей репликой поверх неё (перекрытия реплик организаторы разрешили).
     * @return основной поток слов и вынутые куски. [maxSec] ≤ 0 — выключено.
     */
    fun extractBackchannels(words: List<TimedWord>, maxSec: Float): Pair<List<TimedWord>, List<List<TimedWord>>> {
        if (maxSec <= 0f) return words to emptyList()
        val drop = BooleanArray(words.size)
        val extra = ArrayList<List<TimedWord>>()
        for (r in sentenceRanges(words)) {
            if (r.last == r.first) continue
            val main = mainSpeaker(words, r)
            var i = r.first
            while (i <= r.last) {
                var j = i
                while (j < r.last && words[j + 1].speaker == words[i].speaker) j++
                if (words[i].speaker != main && j - i < 2 && words[j].end - words[i].start < maxSec &&
                    (i..j).all { isBackchannel(words[it].text) }
                ) {
                    for (k in i..j) drop[k] = true
                    extra += words.subList(i, j + 1).toList()
                }
                i = j + 1
            }
        }
        return words.filterIndexed { n, _ -> !drop[n] } to extra
    }

    /**
     * Сглаживание меток внутри предложения. Диаризация режет по звуку, а не по смыслу: когда второй
     * собеседник поддакивает («да», «угу») поверх чужой речи, граница сегмента съезжает на начало
     * поддакивания, и хвост чужой фразы уходит ему — реплика рвётся посреди предложения.
     *
     * Внутри предложения (границы — .!?…, тире реплики «—» и пауза длиннее [pauseGap] с) находим спикера,
     * который говорит дольше всех, и отдаём ему куски других спикеров короче [maxRunSec] с.
     * Длинные куски не трогаем: это может быть настоящая смена говорящего без знака препинания.
     * [maxRunSec] ≤ 0 — сглаживание выключено.
     */
    fun smoothSpeakers(words: List<TimedWord>, maxRunSec: Float, pauseGap: Float = 1f): List<TimedWord> {
        if (maxRunSec <= 0f || words.size < 2) return words
        val out = words.toMutableList()
        for (r in sentenceRanges(words, pauseGap)) smoothSentence(out, r.first, r.last + 1, maxRunSec)
        return out
    }

    private fun smoothSentence(words: MutableList<TimedWord>, from: Int, to: Int, maxRunSec: Float) {
        if (to - from < 2) return
        if ((from until to).all { words[it].speaker == words[from].speaker }) return
        val main = mainSpeaker(words, from until to)
        var i = from
        while (i < to) {
            var j = i
            while (j + 1 < to && words[j + 1].speaker == words[i].speaker) j++
            if (words[i].speaker != main && words[j].end - words[i].start < maxRunSec) {
                for (k in i..j) words[k] = words[k].copy(speaker = main)
            }
            i = j + 1
        }
    }

    private fun endsSentence(text: String): Boolean = text.trimEnd('»', '"', '\'', ')').let {
        it.endsWith('.') || it.endsWith('!') || it.endsWith('?') || it.endsWith('…') || it.endsWith('—')
    }

    /**
     * Новая реплика — при смене спикера, паузе дольше [splitGap] с или паузе дольше [sentenceGap] с
     * после конца предложения. [extra] — готовые реплики-поддакивания из [extractBackchannels], встают
     * по времени начала. Спикеры нумеруются по порядку появления.
     */
    fun buildUtterances(
        words: List<TimedWord>,
        splitGap: Float,
        sentenceGap: Float = Float.MAX_VALUE,
        extra: List<List<TimedWord>> = emptyList(),
    ): List<Utterance> {
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
        if (extra.isNotEmpty()) {
            extra.filter { it.isNotEmpty() }.forEach { groups += it.toMutableList() }
            groups.sortBy { it.first().start } // сортировка устойчивая
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
