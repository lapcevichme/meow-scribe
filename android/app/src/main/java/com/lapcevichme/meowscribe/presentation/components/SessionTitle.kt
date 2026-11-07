package com.lapcevichme.meowscribe.presentation.components

import com.lapcevichme.meowscribe.domain.model.Session

// Сводка LLM хранится текстом: первая строка — название, дальше пункты «• …».

/** Название от LLM, если сводка уже готова. */
val Session.summaryTitle: String?
    get() = summary?.lineSequence()?.firstOrNull()?.trim()
        ?.takeIf { it.isNotEmpty() && !it.startsWith("•") }

/** Главные пункты сводки без маркеров. */
val Session.summaryPoints: List<String>
    get() = summary?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.startsWith("•") }
        ?.map { it.removePrefix("•").trim() }
        ?.filter { it.isNotEmpty() }
        ?.toList()
        .orEmpty()

/** Что показывать как название записи: название от LLM, иначе имя файла или «Запись …». */
val Session.displayTitle: String get() = summaryTitle ?: title
