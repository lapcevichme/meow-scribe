@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.lapcevichme.meowscribe.presentation.feature.processing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.meowscribe.R
import com.lapcevichme.meowscribe.domain.model.ProcessingProgress
import com.lapcevichme.meowscribe.domain.pipeline.Stage
import com.lapcevichme.meowscribe.presentation.components.summary
import com.lapcevichme.meowscribe.presentation.components.BackTopBar
import com.lapcevichme.meowscribe.presentation.components.SpeakerShape
import com.lapcevichme.meowscribe.presentation.components.formatDuration
import com.lapcevichme.meowscribe.presentation.components.formatRtf
import com.lapcevichme.meowscribe.presentation.components.labelRes
import com.lapcevichme.meowscribe.presentation.components.pluralSpeakers
import com.lapcevichme.meowscribe.presentation.components.speakerIndex
import com.lapcevichme.meowscribe.presentation.components.speakerStyle
import java.util.Locale

@Composable
fun ProcessingScreen(
    onBack: () -> Unit,
    onOpenTranscript: (Long) -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        BackTopBar(onBack = onBack)
        Box(Modifier.weight(1f)) {
            when (val s = state) {
                ProcessingUiState.Loading -> Unit
                ProcessingUiState.NotFound -> CenterMessage("Запись не найдена")
                is ProcessingUiState.Running -> RunningContent(s)
                is ProcessingUiState.Done -> DoneContent(s) { onOpenTranscript(viewModel.sessionId) }
                is ProcessingUiState.Failed -> FailedContent(s, viewModel::retry)
            }
        }
        if (state is ProcessingUiState.Running) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onBack, modifier = Modifier.weight(1f).height(52.dp)) { Text("Свернуть") }
                val cancelling = (state as ProcessingUiState.Running).cancelling
                OutlinedButton(
                    onClick = viewModel::cancel,
                    enabled = !cancelling,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(if (cancelling) "Отменяем…" else "Отменить") }
            }
        }
    }
}

@Composable
private fun RunningContent(state: ProcessingUiState.Running) {
    val p = state.progress
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            state.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            listOfNotNull(state.durationMs?.let(::formatDuration), state.config.summary()).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp).offset(y = (-8).dp),
        )
        if (state.cancelling) CancellingNote(p?.stage)
        Hero(p, state.queued)
        StageList(p)
        if (p != null) Metrics(p)
        if (!p?.partial.isNullOrEmpty()) PartialUtterances(p!!)
        Spacer(Modifier.height(8.dp))
    }
}

/** Отмена не мгновенная: объясняем почему, иначе кажется, что приложение зависло. */
@Composable
private fun CancellingNote(stage: Stage?) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp).padding(top = 2.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onErrorContainer)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Отменяем…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    if (stage == Stage.DIARIZATION) {
                        "Разделение спикеров целиком считает библиотека распознавания, и прервать её посреди расчёта нельзя. " +
                            "Остановимся в ближайшей контрольной точке — на длинной записи это может занять несколько минут."
                    } else {
                        "Остановимся, как только закончится текущий фрагмент."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun Hero(p: ProcessingProgress?, queued: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(32.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ContainedLoadingIndicator(Modifier.size(88.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AnimatedContent(
                        targetState = when {
                            queued -> "В очереди"
                            p == null -> "0%"
                            else -> "${(p.overallFraction * 100).toInt()}%"
                        },
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "percent",
                    ) { text ->
                        Text(text, style = MaterialTheme.typography.displaySmallEmphasized)
                    }
                    Text(
                        text = if (p != null) stringResource(p.stage.labelRes()) else "Подготовка",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    p?.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LinearWavyProgressIndicator(
                progress = { p?.overallFraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StageList(p: ProcessingProgress?) {
    val stages = Stage.entries
    val currentIndex = p?.stage?.ordinal ?: -1
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        stages.forEachIndexed { i, stage ->
            val isDone = i < currentIndex
            val isCurrent = i == currentIndex
            val colors = if (isCurrent) {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            }
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index = i, count = stages.size),
                colors = colors,
                leadingContent = { StageMarker(i + 1, isDone, isCurrent) },
                trailingContent = {
                    val trail = when {
                        isDone -> p?.stageTimesMs?.get(stage)?.let { String.format(Locale.ROOT, "%.1f с", it / 1000.0) }
                        isCurrent -> "${((p?.stageFraction ?: 0f) * 100).toInt()}%"
                        else -> null
                    }
                    trail?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                },
                supportingContent = if (isCurrent && p?.message != null) {
                    { Text(p.message) }
                } else {
                    null
                },
            ) {
                Text(
                    stringResource(stage.labelRes()),
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (!isDone && !isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun StageMarker(number: Int, isDone: Boolean, isCurrent: Boolean) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        when {
            isDone -> {
                Box(
                    Modifier.size(30.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = "Готово",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            isCurrent -> LoadingIndicator(Modifier.size(30.dp))
            else -> Box(
                Modifier.size(26.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Metrics(p: ProcessingProgress) {
    // Прогноз RTF: сколько займёт вся обработка относительно длины аудио, если темп сохранится.
    val rtf = p.audioMs?.takeIf { it > 0 && p.overallFraction > 0.05f }?.let { audio ->
        p.elapsedMs / p.overallFraction / audio
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("RTF ≈", rtf?.let { formatRtf(it.toDouble()) } ?: "—", Modifier.weight(1f))
        MetricTile("Пик RAM", p.peakRamMb?.let { "$it МБ" } ?: "—", Modifier.weight(1f))
        MetricTile("Прошло", formatDuration(p.elapsedMs), Modifier.weight(1f))
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1)
        }
    }
}

@Composable
private fun PartialUtterances(p: ProcessingProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            "Уже распознано",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        p.partial.takeLast(3).forEach { u ->
            AnimatedVisibility(visible = true, enter = fadeIn()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeakerShape(speakerStyle(speakerIndex(u.speakerId)), Modifier.padding(top = 2.dp), size = 20.dp)
                    Text(u.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DoneContent(state: ProcessingUiState.Done, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(120.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp),
            )
        }
        Text("Готово", style = MaterialTheme.typography.displaySmallEmphasized)
        Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val details = listOfNotNull(
            pluralSpeakers(state.speakerCount),
            state.processingMs?.let { "обработано за ${formatDuration(it)}" },
            state.rtf?.let { "RTF ${formatRtf(it)}" },
        )
        Text(details.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onOpen, modifier = Modifier.height(56.dp)) {
            Text("Открыть расшифровку")
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null)
        }
    }
}

@Composable
private fun FailedContent(state: ProcessingUiState.Failed, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(104.dp).clip(MaterialShapes.Square.toShape()).background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(state.title, style = MaterialTheme.typography.titleMedium)
        Text(state.message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry, modifier = Modifier.height(52.dp)) {
            Icon(painterResource(R.drawable.ic_redo), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Повторить")
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
