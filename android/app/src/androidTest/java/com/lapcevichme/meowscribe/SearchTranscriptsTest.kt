package com.lapcevichme.meowscribe

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lapcevichme.meowscribe.data.db.MeowDatabase
import com.lapcevichme.meowscribe.data.repository.SessionRepositoryImpl
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Utterance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Поиск по расшифровкам на настоящей SQLite: кириллица, регистр, ё. В LIKE это ломается, поэтому проверяем. */
@RunWith(AndroidJUnit4::class)
class SearchTranscriptsTest {

    private lateinit var db: MeowDatabase
    private lateinit var repo: SessionRepositoryImpl
    private var vet = 0L
    private var cat = 0L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, MeowDatabase::class.java).build()
        repo = SessionRepositoryImpl(db.sessionDao())
        vet = repo.createSession("Созвон с ветеринаром", "file:///a.m4a", 60_000, PipelineConfig())
        cat = repo.createSession("Совещание у миски", "file:///b.m4a", 60_000, PipelineConfig())
        repo.saveResult(vet, listOf(u("Добрый день! Кот плохо ест."), u("Давайте посмотрим анализы.")), 1000)
        repo.saveResult(cat, listOf(u("Кто съел корм из синей МИСКИ?"), u("Точно не я. Ещё вопросы?")), 1000)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun cyrillicIgnoresCase() = runBlocking {
        val result = repo.searchTranscripts("миски").first()
        assertEquals(setOf(cat), result.keys)
        assertEquals("Кто съел корм из синей МИСКИ?", result[cat])
    }

    @Test
    fun upperCaseQuery() = runBlocking {
        assertEquals(setOf(vet), repo.searchTranscripts("АНАЛИЗЫ").first().keys)
    }

    @Test
    fun firstMatchingUtteranceIsSnippet() = runBlocking {
        assertEquals("Точно не я. Ещё вопросы?", repo.searchTranscripts("ещё").first()[cat])
    }

    @Test
    fun blankQueryReturnsNothing() = runBlocking {
        assertTrue(repo.searchTranscripts("   ").first().isEmpty())
    }

    @Test
    fun noMatch() = runBlocking {
        assertTrue(repo.searchTranscripts("собака").first().isEmpty())
    }

    @Test
    fun updatesAfterEdit() = runBlocking {
        repo.updateUtterance(vet, 1, "Давайте посмотрим миску.", "speaker_2")
        assertEquals(setOf(vet, cat), repo.searchTranscripts("миск").first().keys)
    }

    private fun u(text: String) = Utterance("speaker_1", 0, 1000, text)
}
