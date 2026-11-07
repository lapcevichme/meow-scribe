package com.lapcevichme.meowscribe.domain.model

/** Слово с таймстемпами от ASR. Нужно для караоке-подсветки и склейки со спикерами. */
data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/** Одна реплика. Ровно то, что уходит в экспорт, плюс опциональные слова. */
data class Utterance(
    val speakerId: String, // "speaker_1", "speaker_2", ...
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word> = emptyList(),
)
