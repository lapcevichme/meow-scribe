package com.lapcevichme.meowscribe.presentation.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.lapcevichme.meowscribe.R

// Шрифты как в презентации: Unbounded — крупные заголовки, Roboto Flex — всё остальное.
// Оба вариативные и лежат в res/font (сети у приложения нет, загружаемые Google Fonts не подходят).
// Лицензия SIL OFL 1.1 — assets/licenses.

private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w ->
        @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val Unbounded = variable(R.font.unbounded, 400, 500, 600, 700, 800)
val RobotoFlex = variable(R.font.roboto_flex, 400, 500, 600, 700, 800)

/** Unbounded широкий — чуть поджимаем трекинг, чтобы заголовки не расползались. */
private fun TextStyle.display(weight: FontWeight, tracking: Float) =
    copy(fontFamily = Unbounded, fontWeight = weight, letterSpacing = tracking.em)

private fun TextStyle.text() = copy(fontFamily = RobotoFlex)

private val base = Typography()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val Typography = Typography(
    displayLarge = base.displayLarge.display(FontWeight.Medium, -0.02f),
    displayMedium = base.displayMedium.display(FontWeight.Medium, -0.02f),
    displaySmall = base.displaySmall.display(FontWeight.Medium, -0.015f),
    headlineLarge = base.headlineLarge.display(FontWeight.Medium, -0.01f),
    headlineMedium = base.headlineMedium.display(FontWeight.Medium, -0.01f),
    headlineSmall = base.headlineSmall.display(FontWeight.Medium, -0.01f),
    titleLarge = base.titleLarge.text(),
    titleMedium = base.titleMedium.text(),
    titleSmall = base.titleSmall.text(),
    bodyLarge = base.bodyLarge.text(),
    bodyMedium = base.bodyMedium.text(),
    bodySmall = base.bodySmall.text(),
    labelLarge = base.labelLarge.text(),
    labelMedium = base.labelMedium.text(),
    labelSmall = base.labelSmall.text(),
    displayLargeEmphasized = base.displayLargeEmphasized.display(FontWeight.Bold, -0.02f),
    displayMediumEmphasized = base.displayMediumEmphasized.display(FontWeight.Bold, -0.02f),
    displaySmallEmphasized = base.displaySmallEmphasized.display(FontWeight.Bold, -0.015f),
    headlineLargeEmphasized = base.headlineLargeEmphasized.display(FontWeight.SemiBold, -0.01f),
    headlineMediumEmphasized = base.headlineMediumEmphasized.display(FontWeight.SemiBold, -0.01f),
    headlineSmallEmphasized = base.headlineSmallEmphasized.display(FontWeight.SemiBold, -0.01f),
    titleLargeEmphasized = base.titleLargeEmphasized.text(),
    titleMediumEmphasized = base.titleMediumEmphasized.text(),
    titleSmallEmphasized = base.titleSmallEmphasized.text(),
    bodyLargeEmphasized = base.bodyLargeEmphasized.text(),
    bodyMediumEmphasized = base.bodyMediumEmphasized.text(),
    bodySmallEmphasized = base.bodySmallEmphasized.text(),
    labelLargeEmphasized = base.labelLargeEmphasized.text(),
    labelMediumEmphasized = base.labelMediumEmphasized.text(),
    labelSmallEmphasized = base.labelSmallEmphasized.text(),
)
