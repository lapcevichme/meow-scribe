package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Склеивает реплику с соседней. Нужна, когда VAD или диаризация разрезали одну фразу:
 * текст соединяется через пробел (слова не меняются), время — от начала первой до конца второй.
 */
class MergeUtterancesUseCase @Inject constructor(
    private val repository: SessionRepository,
) {
    /**
     * @param text и [speakerId] — текущее состояние редактируемой реплики (правки из шторки не теряются).
     * @return false, если соседней реплики нет.
     */
    suspend operator fun invoke(sessionId: Long, index: Int, withNext: Boolean, text: String, speakerId: String): Boolean {
        val list = repository.getTranscript(sessionId)
        val current = list.getOrNull(index)?.copy(text = text.trim(), speakerId = speakerId) ?: return false
        val otherIndex = if (withNext) index + 1 else index - 1
        val other = list.getOrNull(otherIndex) ?: return false
        val merged = if (withNext) merge(current, other, speakerId) else merge(other, current, speakerId)
        val lo = minOf(index, otherIndex)
        repository.replaceTranscript(sessionId, list.subList(0, lo) + merged + list.subList(lo + 2, list.size))
        return true
    }

    companion object {
        fun merge(first: Utterance, second: Utterance, speakerId: String) = Utterance(
            speakerId = speakerId,
            startMs = minOf(first.startMs, second.startMs),
            endMs = maxOf(first.endMs, second.endMs),
            text = listOf(first.text.trim(), second.text.trim()).filter { it.isNotEmpty() }.joinToString(" "),
            words = first.words + second.words,
        )
    }
}
