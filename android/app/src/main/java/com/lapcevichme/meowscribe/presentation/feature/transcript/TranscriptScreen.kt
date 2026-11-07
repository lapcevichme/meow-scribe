@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.lapcevichme.meowscribe.presentation.feature.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.meowscribe.R
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.usecase.ExportTranscriptUseCase
import com.lapcevichme.meowscribe.presentation.components.BackTopBar
import com.lapcevichme.meowscribe.presentation.components.SpeakerAvatar
import com.lapcevichme.meowscribe.presentation.components.SpeakerShape
import com.lapcevichme.meowscribe.presentation.components.SpeakerStyle
import com.lapcevichme.meowscribe.presentation.components.defaultSpeakerName
import com.lapcevichme.meowscribe.presentation.components.formatDuration
import com.lapcevichme.meowscribe.presentation.components.formatRtf
import com.lapcevichme.meowscribe.presentation.components.pluralSpeakers
import com.lapcevichme.meowscribe.presentation.components.speakerIndex
import com.lapcevichme.meowscribe.presentation.components.speakerInitial
import com.lapcevichme.meowscribe.presentation.components.speakerStyle
import java.util.Locale

@Composable
fun TranscriptScreen(
    onBack: () -> Unit,
    viewModel: TranscriptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player = viewModel.player.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TranscriptEvent.Message -> snackbar.showSnackbar(event.text)
                is TranscriptEvent.Share -> context.shareJson(event.uri)
            }
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportTo(it.toString()) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(onBack = onBack) {
                val content = state as? TranscriptUiState.Content
                if (content != null) {
                    ExportButton(
                        onSaveAs = { saveAs.launch(ExportTranscriptUseCase.exportFileName(content.session.title)) },
                        onDownloads = viewModel::exportToDownloads,
                        onShare = viewModel::share,
                        onCopyText = { context.copyToClipboard(plainText(content)) },
                    )
                }
            }
            when (val s = state) {
                TranscriptUiState.Loading -> Unit
                TranscriptUiState.NotFound -> CenterText("Запись не найдена")
                is TranscriptUiState.NotReady -> CenterText(
                    if (s.status == SessionStatus.ERROR) "Обработка завершилась ошибкой" else "Расшифровка ещё не готова",
                )
                is TranscriptUiState.Content -> TranscriptBody(
                    content = s,
                    player = player,
                    onSeek = viewModel::seekTo,
                    onRename = viewModel::renameSpeaker,
                    onEdit = viewModel::editUtterance,
                    onSplit = viewModel::split,
                    onCopy = { context.copyToClipboard(it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val content = state as? TranscriptUiState.Content
        if (content != null) {
            PlayerToolbar(
                content = content,
                player = player,
                onToggle = viewModel::togglePlay,
                onSeek = { viewModel.seekTo(it) },
                onSpeed = viewModel::cycleSpeed,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
    }
}

// ---------------------------------------------------------------- body

private data class UtteranceGroup(val speakerId: String, val indices: List<Int>)

private sealed interface Row {
    val key: String
    data object Header : Row { override val key = "header" }
    data class GroupHeader(val group: UtteranceGroup, val groupIndex: Int) : Row { override val key = "g$groupIndex" }
    data class Item(val index: Int, val positionInGroup: Int, val groupSize: Int, val groupIndex: Int) : Row {
        override val key = "u$index"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptBody(
    content: TranscriptUiState.Content,
    player: State<PlayerUiState>,
    onSeek: (Long, Boolean) -> Unit,
    onRename: (String, String?) -> Unit,
    onEdit: (Int, String, String) -> Unit,
    onSplit: (Int, String, Int, String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val utterances = content.utterances
    val rows = remember(utterances) { buildRows(utterances) }
    val lazyIndexOf = remember(rows) {
        buildMap { rows.forEachIndexed { i, r -> if (r is Row.Item) put(r.index, i) } }
    }
    val activeIndex by remember(utterances) {
        derivedStateOf { activeUtterance(utterances, player.value.positionMs) }
    }
    val activeGroup by remember(rows) {
        derivedStateOf { rows.firstNotNullOfOrNull { (it as? Row.Item)?.takeIf { r -> r.index == activeIndex }?.groupIndex } ?: -1 }
    }
    val isPlaying by remember { derivedStateOf { player.value.isPlaying } }
    val listState = rememberLazyListState()

    var renaming by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }

    // Следуем за плеером, пока идёт воспроизведение.
    LaunchedEffect(activeIndex, isPlaying) {
        if (isPlaying && activeIndex >= 0) {
            val target = lazyIndexOf[activeIndex] ?: return@LaunchedEffect
            listState.animateScrollToItem((target - 1).coerceAtLeast(0))
        }
    }

    fun nameOf(id: String) = content.speakerNames[id] ?: defaultSpeakerName(id)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 140.dp),
    ) {
        items(rows.size, key = { rows[it].key }) { i ->
            when (val row = rows[i]) {
                Row.Header -> TranscriptHeader(content, ::nameOf, onRenameClick = { renaming = it })
                is Row.GroupHeader -> GroupHeader(
                    speakerId = row.group.speakerId,
                    name = nameOf(row.group.speakerId),
                    startMs = utterances[row.group.indices.first()].startMs,
                    rotating = isPlaying && activeGroup == row.groupIndex,
                    onClick = { renaming = row.group.speakerId },
                )
                is Row.Item -> {
                    val u = utterances[row.index]
                    UtteranceBubble(
                        utterance = u,
                        style = speakerStyle(speakerIndex(u.speakerId)),
                        isActive = row.index == activeIndex,
                        isFirst = row.positionInGroup == 0,
                        isLast = row.positionInGroup == row.groupSize - 1,
                        positionMs = { player.value.positionMs },
                        onClick = { onSeek(u.startMs, true) },
                        onLongClick = { editing = row.index },
                    )
                }
            }
        }
    }

    renaming?.let { id ->
        RenameDialog(
            current = content.speakerNames[id].orEmpty(),
            placeholder = defaultSpeakerName(id),
            onDismiss = { renaming = null },
            onSave = { onRename(id, it); renaming = null },
        )
    }
    editing?.let { index ->
        EditSheet(
            utterance = utterances[index],
            speakerIds = content.speakerIds,
            nameOf = ::nameOf,
            onDismiss = { editing = null },
            onPlayFrom = { onSeek(utterances[index].startMs, true); editing = null },
            onSave = { text, speaker -> onEdit(index, text, speaker); editing = null },
            onSplit = { text, cursor, speaker -> onSplit(index, text, cursor, speaker); editing = null },
            onCopy = { onCopy(it); editing = null },
        )
    }
}

private fun buildRows(utterances: List<Utterance>): List<Row> {
    val groups = mutableListOf<UtteranceGroup>()
    utterances.forEachIndexed { i, u ->
        val last = groups.lastOrNull()
        if (last != null && last.speakerId == u.speakerId) groups[groups.lastIndex] = last.copy(indices = last.indices + i)
        else groups += UtteranceGroup(u.speakerId, listOf(i))
    }
    return buildList {
        add(Row.Header)
        groups.forEachIndexed { gi, g ->
            add(Row.GroupHeader(g, gi))
            g.indices.forEachIndexed { pos, idx -> add(Row.Item(idx, pos, g.indices.size, gi)) }
        }
    }
}

/** Последняя реплика, начавшаяся не позже позиции плеера. */
private fun activeUtterance(utterances: List<Utterance>, positionMs: Long): Int {
    var lo = 0
    var hi = utterances.lastIndex
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (utterances[mid].startMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

@Composable
private fun TranscriptHeader(
    content: TranscriptUiState.Content,
    nameOf: (String) -> String,
    onRenameClick: (String) -> Unit,
) {
    val s = content.session
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.title, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.padding(horizontal = 4.dp))
        val meta = listOfNotNull(
            s.durationMs?.let(::formatDuration),
            pluralSpeakers(content.speakerIds.size),
            s.processingMs?.let { "обработано за ${formatDuration(it)}" },
            s.rtf?.let { "RTF ${formatRtf(it)}" },
        )
        Text(
            meta.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content.speakerIds.forEach { id ->
                Surface(
                    onClick = { onRenameClick(id) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SpeakerShape(speakerStyle(speakerIndex(id)), size = 24.dp)
                        Text(nameOf(id), style = MaterialTheme.typography.labelLarge)
                        Icon(
                            painterResource(R.drawable.ic_edit),
                            contentDescription = "Переименовать",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        s.summary?.let { summary ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Сводка", style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(speakerId: String, name: String, startMs: Long, rotating: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp, start = 4.dp)
            .combinedClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SpeakerAvatar(
            style = speakerStyle(speakerIndex(speakerId)),
            label = speakerInitial(name, speakerId),
            size = 36.dp,
            rotating = rotating,
        )
        Text(name, style = MaterialTheme.typography.titleSmall)
        Text(formatDuration(startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UtteranceBubble(
    utterance: Utterance,
    style: SpeakerStyle,
    isActive: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    positionMs: () -> Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val top by animateDpAsState(if (isActive || isFirst) 24.dp else 6.dp, spatial, label = "top")
    val bottom by animateDpAsState(if (isActive || isLast) 24.dp else 6.dp, spatial, label = "bottom")
    val scale by animateFloatAsState(if (isActive) 1.02f else 1f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "scale")
    val container by animateColorAsState(if (isActive) style.accent else style.container, effects, label = "bg")
    val textColor by animateColorAsState(if (isActive) style.onAccent else style.onContainer, effects, label = "fg")

    Box(
        Modifier
            .padding(vertical = 1.5.dp)
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Изменить реплику")
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${formatDuration(utterance.startMs)} – ${formatDuration(utterance.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
            )
            if (isActive) {
                KaraokeText(utterance, positionMs, textColor)
            } else {
                Text(utterance.text, style = MaterialTheme.typography.bodyLarge, color = textColor)
            }
        }
    }
}

/** Уже произнесённые слова яркие, оставшиеся приглушены. */
@Composable
private fun KaraokeText(u: Utterance, positionMs: () -> Long, color: Color) {
    val pos = positionMs()
    val text = u.text
    val cut: Int = run {
        val tokens = text.split(' ')
        if (u.words.size == tokens.size && u.words.isNotEmpty()) {
            val spoken = u.words.count { it.startMs <= pos }
            tokens.take(spoken).sumOf { it.length + 1 }.coerceAtMost(text.length)
        } else {
            val span = (u.endMs - u.startMs).coerceAtLeast(1)
            val frac = ((pos - u.startMs).toFloat() / span).coerceIn(0f, 1f)
            val raw = (frac * text.length).toInt()
            text.indexOf(' ', raw).let { if (it < 0) text.length else it }
        }
    }
    val annotated: AnnotatedString = buildAnnotatedString {
        append(text.substring(0, cut))
        withStyle(SpanStyle(color = color.copy(alpha = 0.55f))) { append(text.substring(cut)) }
    }
    Text(annotated, style = MaterialTheme.typography.bodyLarge, color = color)
}

// ---------------------------------------------------------------- player

@Composable
private fun PlayerToolbar(
    content: TranscriptUiState.Content,
    player: State<PlayerUiState>,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = player.value
    // Длина шкалы — максимум из длительности аудио и конца последней реплики, чтобы ничего не вылезало.
    val total = maxOf(
        p.durationMs,
        content.session.durationMs ?: 0L,
        content.utterances.maxOfOrNull { it.endMs } ?: 0L,
        1L,
    )
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onToggle) {
                Icon(
                    painterResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (p.isPlaying) "Пауза" else "Воспроизвести",
                )
            }
        },
    ) {
        TextButton(onClick = onSpeed, modifier = Modifier.semantics { contentDescription = "Скорость ${p.speed}" }) {
            Text(speedLabel(p.speed), fontWeight = FontWeight.Bold)
        }
        Column(Modifier.width(168.dp).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SpeakerTimeline(
                utterances = content.utterances,
                totalMs = total,
                positionMs = p.positionMs,
                onSeek = { fraction -> onSeek((fraction * total).toLong()) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(p.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(total), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else String.format(Locale.ROOT, "%.2g×", speed)

/** Таймлайн, раскрашенный по спикерам: результат диаризации виден сразу. Тап/перетаскивание — перемотка. */
@Composable
private fun SpeakerTimeline(
    utterances: List<Utterance>,
    totalMs: Long,
    positionMs: Long,
    onSeek: (Float) -> Unit,
) {
    val colors = utterances.map { speakerStyle(speakerIndex(it.speakerId)).accent }
    val track = MaterialTheme.colorScheme.outlineVariant
    val head = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clipToBounds()
            .pointerInput(totalMs) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(totalMs) {
                detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(track, Offset(0f, h / 2 - 2.dp.toPx()), Size(w, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        val segH = 14.dp.toPx()
        utterances.forEachIndexed { i, u ->
            val x0 = (u.startMs.toFloat() / totalMs * w).coerceIn(0f, w)
            val x1 = (u.endMs.toFloat() / totalMs * w).coerceIn(x0 + 2f, w.coerceAtLeast(x0 + 2f))
            drawRoundRect(
                color = colors[i].copy(alpha = if (u.startMs <= positionMs) 1f else 0.4f),
                topLeft = Offset(x0, h / 2 - segH / 2),
                size = Size(x1 - x0, segH),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
        val px = (positionMs.toFloat() / totalMs * w).coerceIn(0f, w)
        drawRoundRect(head, Offset(px - 2.dp.toPx(), 0f), Size(4.dp.toPx(), h), CornerRadius(2.dp.toPx()))
    }
}

// ---------------------------------------------------------------- export

@Composable
private fun ExportButton(
    onSaveAs: () -> Unit,
    onDownloads: () -> Unit,
    onShare: () -> Unit,
    onCopyText: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(onClick = onSaveAs) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("JSON")
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    checked = menu,
                    onCheckedChange = { menu = it },
                    modifier = Modifier.semantics { contentDescription = "Другие варианты экспорта" },
                ) {
                    val rotation by animateFloatAsState(if (menu) 180f else 0f, label = "chevron")
                    Icon(
                        painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                    )
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("В «Загрузки»") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_download), null) },
                onClick = { menu = false; onDownloads() },
            )
            DropdownMenuItem(
                text = { Text("Поделиться") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_share), null) },
                onClick = { menu = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text("Копировать текст") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) },
                onClick = { menu = false; onCopyText() },
            )
        }
    }
}

private fun plainText(content: TranscriptUiState.Content): String =
    content.utterances.joinToString("\n") { u ->
        val name = content.speakerNames[u.speakerId] ?: defaultSpeakerName(u.speakerId)
        "[${formatDuration(u.startMs)}] $name: ${u.text}"
    }

private fun Context.copyToClipboard(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("MeowScribe", text))
}

private fun Context.shareJson(uri: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "Поделиться расшифровкой"))
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun RenameDialog(current: String, placeholder: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Имя спикера") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(placeholder) },
                )
                Text(
                    "Имя видно только в приложении. В JSON остаётся speaker_id.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Сохранить") } },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton(onClick = { onSave(null) }) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(
    utterance: Utterance,
    speakerIds: List<String>,
    nameOf: (String) -> String,
    onDismiss: () -> Unit,
    onPlayFrom: () -> Unit,
    onSave: (String, String) -> Unit,
    onSplit: (String, Int, String) -> Unit,
    onCopy: (String) -> Unit,
) {
    var field by remember(utterance) { mutableStateOf(TextFieldValue(utterance.text, TextRange(utterance.text.length))) }
    val text = field.text
    val cursor = field.selection.start
    val canSplit = text.substring(0, cursor.coerceIn(0, text.length)).isNotBlank() &&
        text.substring(cursor.coerceIn(0, text.length)).isNotBlank()
    var speaker by remember(utterance) { mutableStateOf(utterance.speakerId) }
    val newSpeakerId = remember(speakerIds) {
        "speaker_${(speakerIds.maxOfOrNull { speakerIndex(it) } ?: -1) + 2}"
    }
    val options = speakerIds + newSpeakerId

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Реплика · ${formatDuration(utterance.startMs)}–${formatDuration(utterance.endMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Текст") },
                supportingText = { Text("Чтобы разделить реплику, поставьте курсор на границу") },
            )
            Text("Кто говорит", style = MaterialTheme.typography.titleSmall)
            // Кнопки по ширине содержимого, при нехватке места ряд прокручивается: имена не обрезаются.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                options.forEachIndexed { i, id ->
                    val isNew = id == newSpeakerId
                    ToggleButton(
                        checked = speaker == id,
                        onCheckedChange = { speaker = id },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) {
                        if (!isNew) {
                            SpeakerShape(speakerStyle(speakerIndex(id)), size = 18.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (isNew) "Новый" else nameOf(id), maxLines = 1)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                SheetAction(R.drawable.ic_play_from, "Воспроизвести отсюда", onClick = onPlayFrom)
                SheetAction(
                    R.drawable.ic_split,
                    "Разделить реплику по курсору",
                    enabled = canSplit,
                    onClick = { onSplit(text, cursor, speaker) },
                )
                SheetAction(R.drawable.ic_copy, "Копировать текст", onClick = { onCopy(text) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Отмена") }
                Button(
                    onClick = { onSave(text, speaker) },
                    enabled = text.isNotBlank() && (text != utterance.text || speaker != utterance.speakerId),
                ) { Text("Сохранить") }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val tint = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f)
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
