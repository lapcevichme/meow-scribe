@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.lapcevichme.meowscribe.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon

/**
 * Визуальная идентичность спикера: роль цвета из схемы + форма из MaterialShapes.
 * Цветовых семейств в M3 три (primary / tertiary / secondary), поэтому начиная с 4-го спикера
 * цвет нейтральный, а различие держится на форме.
 */
@Immutable
data class SpeakerStyle(
    val container: Color,
    val onContainer: Color,
    val accent: Color,
    val onAccent: Color,
    val polygon: RoundedPolygon,
)

/** "speaker_3" → 2. Нестандартные id раскладываются по хэшу. */
fun speakerIndex(speakerId: String): Int =
    speakerId.substringAfterLast('_').toIntOrNull()?.minus(1)?.coerceAtLeast(0)
        ?: (speakerId.hashCode() and 0x7fffffff)

fun defaultSpeakerName(speakerId: String): String = "Спикер ${speakerIndex(speakerId) + 1}"

/** Для имени по умолчанию («Спикер 2») — номер, для своего имени — первая буква. */
fun speakerInitial(name: String, speakerId: String): String {
    val number = "${speakerIndex(speakerId) + 1}"
    if (name == defaultSpeakerName(speakerId)) return number
    return name.trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: number
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun speakerStyle(index: Int): SpeakerStyle {
    val cs = MaterialTheme.colorScheme
    val i = index % SHAPE_COUNT
    val polygon = when (i) {
        0 -> MaterialShapes.Cookie9Sided
        1 -> MaterialShapes.Clover4Leaf
        2 -> MaterialShapes.SoftBurst
        3 -> MaterialShapes.Pentagon
        4 -> MaterialShapes.Clover8Leaf
        else -> MaterialShapes.Triangle
    }
    return when (i) {
        0 -> SpeakerStyle(cs.primaryContainer, cs.onPrimaryContainer, cs.primary, cs.onPrimary, polygon)
        1 -> SpeakerStyle(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary, polygon)
        2 -> SpeakerStyle(cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary, polygon)
        else -> SpeakerStyle(cs.surfaceContainerHighest, cs.onSurface, cs.inverseSurface, cs.inverseOnSurface, polygon)
    }
}

private const val SHAPE_COUNT = 6

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
/** Аватар спикера: форма MaterialShapes в акцентном цвете, буква поверх. Может медленно вращаться. */
@Composable
fun SpeakerAvatar(
    style: SpeakerStyle,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    rotating: Boolean = false,
) {
    val shape = style.polygon.toShape()
    val rotation = if (rotating) {
        val transition = rememberInfiniteTransition(label = "avatar")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
            label = "rotation",
        ).value
    } else {
        0f
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = rotation }
                .clip(shape)
                .background(style.accent),
        )
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = style.onAccent,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
/** Небольшая фигура без текста (стопки спикеров в списке, легенды). */
@Composable
fun SpeakerShape(style: SpeakerStyle, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Box(
        modifier
            .size(size)
            .clip(style.polygon.toShape())
            .background(style.accent),
    )
}
