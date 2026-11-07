package com.lapcevichme.meowscribe.presentation.components

import android.text.format.DateUtils
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Profile
import java.util.Locale

/** 75_000 → "1:15", 3_700_000 → "1:01:40". */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** «сегодня, 13:40» / «вчера, 09:12» / «5 нояб., 18:00». */
fun formatCreatedAt(epochMs: Long): String {
    val time = String.format(Locale.ROOT, "%tH:%<tM", epochMs)
    return when {
        DateUtils.isToday(epochMs) -> "сегодня, $time"
        DateUtils.isToday(epochMs + DateUtils.DAY_IN_MILLIS) -> "вчера, $time"
        else -> String.format(Locale("ru"), "%te %<tb, %s", epochMs, time)
    }
}

fun formatRtf(rtf: Double): String = String.format(Locale.ROOT, "%.2f", rtf)

fun pluralSpeakers(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "спикер"
        mod10 in 2..4 && mod100 !in 12..14 -> "спикера"
        else -> "спикеров"
    }
    return "$n $word"
}

fun Profile.label(): String = when (this) {
    Profile.FAST -> "Быстрый"
    Profile.ACCURATE -> "Точный"
}

fun languageLabel(code: String): String = when (code) {
    "ru" -> "Русский"
    "en" -> "English"
    else -> "Авто"
}

/** «Профиль «Быстрый» · RU» — подпись под названием записи. */
fun PipelineConfig.summary(): String = buildList {
    add("Профиль «${profile.label()}»")
    add(if (language == "auto") "язык авто" else language.uppercase())
    numSpeakers?.let { add(pluralSpeakers(it)) }
}.joinToString(" · ")
