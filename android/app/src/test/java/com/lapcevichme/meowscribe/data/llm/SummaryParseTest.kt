package com.lapcevichme.meowscribe.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryParseTest {

    @Test
    fun `parses title and bullets`() {
        val s = LlmSummarizer.parse(
            "Название: Поиск корма\nРезюме:\n- Мурзик съел корм.\n- Коты ищут пакет в шкафу.\n- Барсик просит убрать за собой.",
        )!!
        assertEquals("Поиск корма", s.title)
        assertEquals(3, s.bullets.size)
        assertEquals("Мурзик съел корм.", s.bullets[0])
    }

    @Test
    fun `returns null without title`() {
        assertNull(LlmSummarizer.parse("Резюме:\n- что-то"))
    }
}
