package com.lapcevichme.meowscribe.domain.export

import com.lapcevichme.meowscribe.domain.model.Utterance

/** Человекочитаемые форматы: TXT и SRT. JSON по схеме конкурса живёт в TranscriptExporter. */
object TranscriptFormatter {

    /** Субтитр больше двух строк по 42 символа читать неудобно. */
    const val SRT_LINE = 42
    const val SRT_MAX_CHARS = SRT_LINE * 2
    const val SRT_MAX_MS = 7_000L
    private const val MIN_WORDS = 6
    /** На сколько символов можно сдвинуть разрыв ради знака препинания. */
    private const val PUNCT_SLACK = 16

    fun defaultName(speakerId: String): String =
        speakerId.removePrefix("speaker_").toIntOrNull()?.let { "Спикер $it" } ?: speakerId

    private fun label(speakerId: String, names: Map<String, String>, useNames: Boolean) =
        if (useNames) names[speakerId] ?: defaultName(speakerId) else speakerId

    /**
     * Подряд идущие реплики одного спикера собираются под одним заголовком, как в мессенджере:
     *
     *     [00:12] Анна
     *     Добрый день!
     *     Вы не видели мою миску?
     */
    fun txt(
        header: List<String>,
        utterances: List<Utterance>,
        names: Map<String, String>,
        useNames: Boolean,
        timestamps: Boolean,
    ): String = buildString {
        header.filter { it.isNotBlank() }.forEach { appendLine(it) }
        if (header.isNotEmpty()) appendLine()
        var i = 0
        while (i < utterances.size) {
            val speaker = utterances[i].speakerId
            if (timestamps) append('[').append(clock(utterances[i].startMs)).append("] ")
            appendLine(label(speaker, names, useNames))
            while (i < utterances.size && utterances[i].speakerId == speaker) {
                appendLine(utterances[i].text.trim())
                i++
            }
            if (i < utterances.size) appendLine()
        }
    }.trimEnd() + "\n"

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Длинные реплики режутся на субтитры по ≤ 84 символа и ≤ 7 с, имя спикера — в начале реплики. */
    fun srtCues(utterances: List<Utterance>, names: Map<String, String>, useNames: Boolean): List<Cue> =
        utterances.flatMap { u ->
            val chunks = chunk(u)
            chunks.mapIndexed { i, c ->
                val prefix = if (i == 0) "${label(u.speakerId, names, useNames)}: " else ""
                c.copy(text = wrap(prefix + c.text))
            }
        }

    fun srt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            appendLine(i + 1)
            append(srtTime(c.startMs)).append(" --> ").appendLine(srtTime(maxOf(c.endMs, c.startMs + 1)))
            appendLine(c.text)
            appendLine()
        }
    }

    /** Режет реплику по словам. Время — по таймстемпам слов, если они совпадают с текстом, иначе пропорционально. */
    internal fun chunk(u: Utterance): List<Cue> {
        val text = u.text.trim()
        val start = u.startMs
        val end = maxOf(u.endMs, u.startMs)
        if (text.length <= SRT_MAX_CHARS && end - start <= SRT_MAX_MS) return listOf(Cue(start, end, text))

        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size <= 1) return listOf(Cue(start, end, text))
        val wordTimes = u.words.takeIf { it.size == tokens.size }

        // Позиция начала каждого токена в символах — для пропорционального времени.
        val offsets = IntArray(tokens.size + 1)
        for (k in tokens.indices) offsets[k + 1] = offsets[k] + tokens[k].length + 1
        val totalChars = offsets.last().coerceAtLeast(1)
        fun timeAt(k: Int, isEnd: Boolean): Long = when {
            wordTimes != null && isEnd -> wordTimes[k - 1].endMs
            wordTimes != null -> wordTimes[k].startMs
            else -> start + (end - start) * offsets[k] / totalChars
        }

        // Сколько субтитров нужно: по длине текста и по времени. По времени режем, только пока в куске
        // остаётся хотя бы MIN_WORDS слов: медленную фразу из 7 слов лучше показать целиком, чем по два слова.
        val byChars = (offsets.last() + SRT_MAX_CHARS - 1) / SRT_MAX_CHARS
        val byTime = minOf(((end - start + SRT_MAX_MS - 1) / SRT_MAX_MS).toInt(), tokens.size / MIN_WORDS)
        val n = maxOf(byChars, byTime, 1).coerceAtMost(tokens.size)

        // Границы ставим около равных долей текста; если рядом есть конец фразы или запятая — режем по ним.
        val cuts = mutableListOf(0)
        for (part in 1 until n) {
            val target = offsets.last() * part / n
            val candidates = (cuts.last() + 1 until tokens.size).filter { kotlin.math.abs(offsets[it] - target) <= PUNCT_SLACK }
            val natural = candidates.filter { tokens[it - 1].last() in ".,!?;:…" }
                .minByOrNull { kotlin.math.abs(offsets[it] - target) }
            val nearest = (cuts.last() + 1 until tokens.size).minByOrNull { kotlin.math.abs(offsets[it] - target) }
            val cut = natural ?: nearest ?: break
            if (cut > cuts.last()) cuts += cut
        }
        cuts += tokens.size
        val pieces = cuts.zipWithNext { a, b -> a until b }.filter { !it.isEmpty() }

        return pieces.map { r ->
            Cue(
                startMs = if (r.first == 0) start else timeAt(r.first, isEnd = false),
                endMs = if (r.last == tokens.lastIndex) end else timeAt(r.last + 1, isEnd = true),
                text = tokens.subList(r.first, r.last + 1).joinToString(" "),
            )
        }
    }

    /** Две строки примерно поровну, разрыв по пробелу ближе к середине. */
    internal fun wrap(text: String): String {
        if (text.length <= SRT_LINE) return text
        val mid = text.length / 2
        val left = text.lastIndexOf(' ', mid)
        val right = text.indexOf(' ', mid)
        val cut = listOf(left, right).filter { it > 0 }.minByOrNull { kotlin.math.abs(it - mid) } ?: return text
        return text.substring(0, cut) + "\n" + text.substring(cut + 1)
    }

    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun srtTime(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(v / 3_600_000, v % 3_600_000 / 60_000, v % 60_000 / 1000, v % 1000)
    }
}
