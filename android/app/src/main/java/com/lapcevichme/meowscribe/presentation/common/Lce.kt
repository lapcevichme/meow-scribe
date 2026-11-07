package com.lapcevichme.meowscribe.presentation.common

/**
 * Состояние загрузки данных экрана. Описывает только загрузку контента;
 * плеер, режим редактирования и прочее держим отдельными полями UiState рядом с Lce.
 */
sealed interface Lce<out T> {
    data object Loading : Lce<Nothing>
    data class Content<T>(val data: T) : Lce<T>
    data class Error(val message: String, val cause: Throwable? = null) : Lce<Nothing>
}
