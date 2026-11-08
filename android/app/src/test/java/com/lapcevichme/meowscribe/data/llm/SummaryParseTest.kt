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

    @Test
    fun `copied placeholder is stripped from bullets`() {
        val s = LlmSummarizer.parse("Название: Эфир\nРезюме:\n- Пункт: Свет — волна.\n- Пункт 2. Опыт Майкельсона.\n- Пунктуальность важна.")!!
        assertEquals(listOf("Свет — волна.", "Опыт Майкельсона.", "Пунктуальность важна."), s.bullets)
    }
}
