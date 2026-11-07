package com.lapcevichme.meowscribe.domain.usecase

import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Делит реплику на две по позиции курсора. Нужна, когда диаризация склеила двух говорящих.
 * Граница по времени берётся из таймстемпов слов, а если их нет — пропорционально длине текста.
 */
class SplitUtteranceUseCase @Inject constructor(
    private val repository: SessionRepository,
) {
    /** @return false, если делить нечего (курсор в начале или конце). */
    suspend operator fun invoke(sessionId: Long, index: Int, text: String, cursor: Int, speakerId: String): Boolean {
        val list = repository.getTranscript(sessionId)
        val original = list.getOrNull(index) ?: return false
        val parts = split(original.copy(text = text, speakerId = speakerId), cursor) ?: return false
        repository.replaceTranscript(sessionId, list.subList(0, index) + parts.toList() + list.subList(index + 1, list.size))
        return true
    }

    companion object {
        fun split(u: Utterance, cursor: Int): Pair<Utterance, Utterance>? {
            val pos = cursor.coerceIn(0, u.text.length)
            val leftText = u.text.substring(0, pos).trim()
            val rightText = u.text.substring(pos).trim()
            if (leftText.isEmpty() || rightText.isEmpty()) return null

            val leftTokens = leftText.split(Regex("\\s+")).size
            val tokens = u.text.trim().split(Regex("\\s+")).size
            val wordsAligned = u.words.size == tokens && u.words.size > leftTokens
            val (leftEnd, rightStart) = if (wordsAligned) {
                u.words[leftTokens - 1].endMs to u.words[leftTokens].startMs
            } else {
                val t = u.startMs + (u.endMs - u.startMs) * pos / u.text.length.coerceAtLeast(1)
                t to t
            }
            val left = u.copy(
                text = leftText,
                endMs = leftEnd,
                words = if (wordsAligned) u.words.take(leftTokens) else emptyList(),
            )
            val right = u.copy(
                text = rightText,
                startMs = rightStart,
                words = if (wordsAligned) u.words.drop(leftTokens) else emptyList(),
            )
            return left to right
        }
    }
}
