package com.lapcevichme.meowscribe.presentation.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Запасная схема для устройств без Material You (Android < 12).
// Сгенерирована material-color-utilities: seed #E07A2E («рыжий кот»), вариант Tonal Spot.
// Заменить на экспорт из Material Theme Builder, когда выберем основной цвет.

internal val FallbackLightScheme = lightColorScheme(
    primary = Color(0xFF8B4F25),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC7),
    onPrimaryContainer = Color(0xFF6E380F),
    secondary = Color(0xFF755846),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBC7),
    onSecondaryContainer = Color(0xFF5B4130),
    tertiary = Color(0xFF616134),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE7E6AD),
    onTertiaryContainer = Color(0xFF49491E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A15),
    onSurfaceVariant = Color(0xFF52443C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EA),
    surfaceContainer = Color(0xFFFCEBE2),
    surfaceContainerHigh = Color(0xFFF6E5DC),
    surfaceContainerHighest = Color(0xFFF0DFD7),
    outline = Color(0xFF84746A),
    outlineVariant = Color(0xFFD7C3B8),
    inverseSurface = Color(0xFF382E29),
    inverseOnSurface = Color(0xFFFFEDE5),
)

internal val FallbackDarkScheme = darkColorScheme(
    primary = Color(0xFFFFB689),
    onPrimary = Color(0xFF512400),
    primaryContainer = Color(0xFF6E380F),
    onPrimaryContainer = Color(0xFFFFDBC7),
    secondary = Color(0xFFE5BFA9),
    onSecondary = Color(0xFF432B1C),
    secondaryContainer = Color(0xFF5B4130),
    onSecondaryContainer = Color(0xFFFFDBC7),
    tertiary = Color(0xFFCAC993),
    onTertiary = Color(0xFF323209),
    tertiaryContainer = Color(0xFF49491E),
    onTertiaryContainer = Color(0xFFE7E6AD),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surface = Color(0xFF19120D),
    onSurface = Color(0xFFF0DFD7),
    onSurfaceVariant = Color(0xFFD7C3B8),
    surfaceContainerLowest = Color(0xFF140D08),
    surfaceContainerLow = Color(0xFF221A15),
    surfaceContainer = Color(0xFF261E19),
    surfaceContainerHigh = Color(0xFF312823),
    surfaceContainerHighest = Color(0xFF3D332D),
    outline = Color(0xFF9F8D83),
    outlineVariant = Color(0xFF52443C),
    inverseSurface = Color(0xFFF0DFD7),
    inverseOnSurface = Color(0xFF382E29),
)
