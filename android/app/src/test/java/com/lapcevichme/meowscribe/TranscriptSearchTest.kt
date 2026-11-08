package com.lapcevichme.meowscribe

import com.lapcevichme.meowscribe.presentation.feature.transcript.TranscriptSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSearchTest {

    private val texts = listOf(
        "Эфир — это, конечно, ошибка.",
        "Никакого эфира нет, правда?",
        "Всё делаете не так. Ещё раз!",
        "Свет летит сквозь эфирный ветер.",
    )

    @Test
    fun `word prefix matches russian endings, case and yo insensitive`() {
        assertEquals(listOf(0, 1, 3), TranscriptSearch.find(texts, "ЭФИР").map { it.index })
        assertEquals(listOf(2), TranscriptSearch.find(texts, "еще").map { it.index })
        assertEquals(listOf(0..3), TranscriptSearch.find(texts, "эфир").first().ranges)
        assertEquals(listOf(9..13), TranscriptSearch.find(texts, "эфир")[1].ranges) // «эфира» целиком
    }

    @Test
    fun `all query words must be present, middle of a word does not match`() {
        assertEquals(listOf(3), TranscriptSearch.find(texts, "свет эфир").map { it.index })
        assertEquals(emptyList<Int>(), TranscriptSearch.find(texts, "фир").map { it.index })
        assertEquals(emptyList<Int>(), TranscriptSearch.find(texts, "  ").map { it.index })
    }
}
