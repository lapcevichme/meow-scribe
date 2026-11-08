package com.lapcevichme.meowscribe.presentation.feature.transcript

/**
 * Поиск по репликам расшифровки. Слово запроса ищется как начало слова в реплике: «эфир» находит
 * «эфира», «эфиром» — для русского это закрывает большую часть того, зачем нужен «умный» поиск.
 * Регистр и ё/е не различаются. Реплика подходит, если в ней есть все слова запроса.
 */
internal object TranscriptSearch {

    /** Реплика [index] и диапазоны найденных слов в её тексте (для подсветки). */
    data class Hit(val index: Int, val ranges: List<IntRange>)

    fun find(texts: List<String>, query: String): List<Hit> {
        val tokens = query.split(' ', '\t', '\n')
            .map { t -> normalize(t).filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .distinct()
        if (tokens.isEmpty()) return emptyList()
        return texts.mapIndexedNotNull { i, text ->
            val norm = normalize(text) // та же длина, что у text: индексы совпадают
            val starts = norm.indices.filter { j -> norm[j].isLetterOrDigit() && (j == 0 || !norm[j - 1].isLetterOrDigit()) }
            val ranges = ArrayList<IntRange>()
            val all = tokens.all { tok ->
                var found = false
                for (st in starts) {
                    if (!norm.startsWith(tok, st)) continue
                    var end = st
                    while (end < norm.length && norm[end].isLetterOrDigit()) end++
                    ranges += st until end
                    found = true
                }
                found
            }
            if (all) Hit(i, ranges.distinct().sortedBy { it.first }) else null
        }
    }

    private fun normalize(s: String): String = buildString(s.length) {
        for (c in s) append(if (c == 'ё' || c == 'Ё') 'е' else c.lowercaseChar())
    }
}
