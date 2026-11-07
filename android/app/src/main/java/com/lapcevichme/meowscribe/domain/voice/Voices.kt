package com.lapcevichme.meowscribe.domain.voice

/** Сохранённый голос: имя и усреднённый эмбеддинг по всем записям, где его «запомнили». */
data class VoiceProfile(
    val id: Long,
    val name: String,
    /** Сколько записей усреднено в эмбеддинге. */
    val samples: Int,
    val updatedAt: Long,
)

/** Профиль вместе с вектором — только для сопоставления, в UI не нужен. */
class StoredVoice(
    val id: Long,
    val name: String,
    val samples: Int,
    val embedding: FloatArray,
)

/** Спикер записи, связанный с сохранённым голосом. */
data class VoiceMatch(
    val speakerId: String,
    val profileId: Long,
    val name: String,
    /** Косинусная близость 0..1; null — голос привязан вручную через «Запомнить голос». */
    val similarity: Float?,
)
