@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.lapcevichme.meowscribe.presentation.feature.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.meowscribe.R
import com.lapcevichme.meowscribe.domain.repository.RecorderStatus
import com.lapcevichme.meowscribe.presentation.components.BackTopBar
import com.lapcevichme.meowscribe.presentation.components.formatDuration
import com.lapcevichme.meowscribe.presentation.components.summary

@Composable
fun RecordScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    onSettings: () -> Unit,
    viewModel: RecordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        askedOnce = true
        if (ok) viewModel.toggle()
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordEvent.Message -> snackbar.showSnackbar(event.text)
                is RecordEvent.Saved -> onSaved(event.sessionId)
                RecordEvent.Discarded -> onBack()
            }
        }
    }

    // Экран не гаснет, пока идёт запись.
    val view = LocalView.current
    val active = state.status != RecorderStatus.IDLE
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    var confirmExit by remember { mutableStateOf(false) }
    val tryExit = { if (active) confirmExit = true else onBack() }
    BackHandler(active) { confirmExit = true }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(onBack = tryExit)
            if (!granted) {
                PermissionContent(
                    permanentlyDenied = askedOnce,
                    onRequest = { permission.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                )
            } else {
                RecorderContent(
                    state = state,
                    onToggle = viewModel::toggle,
                    onFinish = viewModel::finish,
                    onDiscard = { confirmExit = true },
                    onSettings = onSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Остановить запись?") },
            text = { Text("Можно сразу отправить её на расшифровку или удалить.") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; viewModel.finish() }) { Text("Расшифровать") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false; viewModel.discard() }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmExit = false }) { Text("Продолжить") }
                }
            },
        )
    }
}

@Composable
private fun RecorderContent(
    state: RecordUiState,
    onToggle: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val recording = state.status == RecorderStatus.RECORDING
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (state.status) {
                RecorderStatus.IDLE -> "Новая запись"
                RecorderStatus.RECORDING -> "Идёт запись"
                RecorderStatus.PAUSED -> "Пауза"
            },
            style = MaterialTheme.typography.headlineMediumEmphasized,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            state.config.summary(),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        TextButton(onClick = onSettings) { Text("Изменить настройки") }

        Spacer(Modifier.weight(1f))
        Blob(level = state.levels.lastOrNull() ?: 0f, recording = recording, paused = state.status == RecorderStatus.PAUSED)
        Spacer(Modifier.height(28.dp))
        Text(
            formatDuration(state.elapsedMs),
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(20.dp))
        Waveform(state.levels, Modifier.fillMaxWidth().height(64.dp))
        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onDiscard,
                enabled = state.status != RecorderStatus.IDLE && !state.isSaving,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Удалить запись")
            }
            MainButton(state.status, enabled = !state.isSaving, onClick = onToggle)
            if (state.isSaving) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            } else {
                FilledIconButton(
                    onClick = onFinish,
                    enabled = state.status != RecorderStatus.IDLE && state.elapsedMs > 0,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = "Готово, расшифровать")
                }
            }
        }
    }
}

/** Пульсирующая форма: вращается, пока идёт запись, и дышит с громкостью голоса. */
@Composable
private fun Blob(level: Float, recording: Boolean, paused: Boolean) {
    val cs = MaterialTheme.colorScheme
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(recording) {
        while (recording) {
            rotation.animateTo(rotation.value + 360f, tween(9000, easing = LinearEasing))
        }
    }
    val pulse by animateFloatAsState(
        if (recording) 1f + level * 0.32f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pulse",
    )
    val container by animateColorAsState(
        if (paused) cs.surfaceContainerHighest else cs.primaryContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "container",
    )
    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(200.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse; rotationZ = -rotation.value * 0.6f; alpha = 0.35f }
                .clip(MaterialShapes.SoftBurst.toShape())
                .background(cs.primary.copy(alpha = if (recording) 0.5f else 0.2f)),
        )
        Box(
            Modifier
                .size(176.dp)
                .graphicsLayer { rotationZ = rotation.value }
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(container),
        )
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = if (paused) cs.onSurfaceVariant else cs.onPrimaryContainer,
            modifier = Modifier.size(56.dp),
        )
    }
}

/** Главная кнопка морфится: круг (старт) → скруглённый квадрат (идёт запись). */
@Composable
private fun MainButton(status: RecorderStatus, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val recording = status == RecorderStatus.RECORDING
    val corner by animateDpAsState(if (recording) 30.dp else 48.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    val color by animateColorAsState(if (recording) cs.tertiary else cs.primary, label = "color")
    val onColor by animateColorAsState(if (recording) cs.onTertiary else cs.onPrimary, label = "onColor")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        color = color,
        contentColor = onColor,
        modifier = Modifier.size(96.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(
                    when (status) {
                        RecorderStatus.IDLE -> R.drawable.ic_mic
                        RecorderStatus.RECORDING -> R.drawable.ic_pause
                        RecorderStatus.PAUSED -> R.drawable.ic_play
                    },
                ),
                contentDescription = when (status) {
                    RecorderStatus.IDLE -> "Начать запись"
                    RecorderStatus.RECORDING -> "Пауза"
                    RecorderStatus.PAUSED -> "Продолжить запись"
                },
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** Столбики громкости, свежие справа. */
@Composable
private fun Waveform(levels: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val barW = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val count = (size.width / (barW + gap)).toInt()
        val minH = 4.dp.toPx()
        for (i in 0 until count) {
            val li = levels.size - count + i
            val level = levels.getOrNull(li)
            val h = if (level == null) minH else (minH + level * (size.height - minH))
            val x = i * (barW + gap)
            drawRoundRect(
                color = if (level == null) idle else color,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}

@Composable
private fun PermissionContent(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(120.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_mic),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Text("Нужен доступ к микрофону", style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Text(
            "Запись остаётся на телефоне и никуда не отправляется.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = if (permanentlyDenied) onOpenSettings else onRequest, shape = CircleShape) {
            Text(if (permanentlyDenied) "Открыть настройки" else "Разрешить")
        }
        if (permanentlyDenied) {
            TextButton(onClick = onRequest) { Text("Спросить ещё раз") }
        }
    }
}
