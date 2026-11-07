@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.lapcevichme.meowscribe.presentation.feature.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.meowscribe.R
import com.lapcevichme.meowscribe.domain.model.Session
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.presentation.components.SpeakerShape
import com.lapcevichme.meowscribe.presentation.components.formatCreatedAt
import com.lapcevichme.meowscribe.presentation.components.formatDuration
import com.lapcevichme.meowscribe.presentation.components.labelRes
import com.lapcevichme.meowscribe.presentation.components.pluralSpeakers
import com.lapcevichme.meowscribe.presentation.components.speakerStyle

private val AUDIO_MIME_TYPES = arrayOf("audio/*", "application/ogg", "video/mp4")

@Composable
fun HomeScreen(
    onOpenSession: (Session) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris.map { it.toString() })
        if (uris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Текст поля держим локально: если гонять его через StateFlow ViewModel, при быстром вводе теряются символы.
    var query by rememberSaveable { mutableStateOf("") }

    HomeContent(
        state = state,
        query = query,
        snackbar = snackbar,
        onImport = { picker.launch(AUDIO_MIME_TYPES) },
        onRecord = onRecord,
        onSettings = onSettings,
        onQuery = { query = it; viewModel.setQuery(it) },
        onFilter = viewModel::setFilter,
        onOpen = onOpenSession,
        onRetry = viewModel::retry,
        onDelete = viewModel::delete,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    query: String,
    snackbar: SnackbarHostState,
    onImport: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (HomeFilter) -> Unit,
    onOpen: (Session) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Session?>(null) }
    BackHandler(fabExpanded) { fabExpanded = false }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 140.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                item(key = "header") { Header(state, onSettings) }
                if (state.totalCount > 0) {
                    item(key = "search") { SearchField(query, onQuery) }
                    item(key = "filters") { Filters(state.filter, onFilter) }
                }
                if (!state.isLoading && state.totalCount == 0) {
                    item(key = "empty") { EmptyState(onImport, onRecord) }
                }
                if (state.totalCount > 0 && state.items.isEmpty() && state.query.isNotEmpty()) {
                    item(key = "nothing") {
                        Text(
                            "По запросу «${state.query}» ничего не нашлось",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                itemsIndexed(state.items, key = { _, it -> it.session.id }) { index, item ->
                    SessionRow(
                        item = item,
                        query = state.query,
                        index = index,
                        count = state.items.size,
                        onClick = { onOpen(item.session) },
                        onLongClick = { pendingDelete = item.session },
                        onRetry = { onRetry(item.session.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            FloatingActionButtonMenu(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = padding.calculateBottomPadding()),
                expanded = fabExpanded,
                button = {
                    ToggleFloatingActionButton(
                        modifier = Modifier.semantics { role = Role.Button },
                        checked = fabExpanded,
                        onCheckedChange = { fabExpanded = !fabExpanded },
                    ) {
                        val icon by remember {
                            derivedStateOf { if (checkedProgress > 0.5f) R.drawable.ic_close else R.drawable.ic_add }
                        }
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = if (fabExpanded) "Закрыть меню" else "Добавить запись",
                            modifier = Modifier.animateIcon({ checkedProgress }),
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; onImport() },
                    icon = { Icon(painterResource(R.drawable.ic_upload), contentDescription = null) },
                    text = { Text("Импорт файлов") },
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; onRecord() },
                    icon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null) },
                    text = { Text("Записать с микрофона") },
                )
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить запись?") },
            text = { Text("«${session.title}» и её расшифровка будут удалены с устройства.") },
            confirmButton = {
                TextButton(onClick = { onDelete(session.id); pendingDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Header(state: HomeUiState, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp, start = 4.dp, end = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape,
            ) {
                Row(
                    Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_phone), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Всё на устройстве", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = onSettings,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Настройки")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Записи", style = MaterialTheme.typography.displayMediumEmphasized)
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isImporting) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoadingIndicator(Modifier.size(28.dp))
                Text("Копируем файлы…", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun subtitle(state: HomeUiState): String {
    if (state.totalCount == 0) return "Расшифровка с разделением по спикерам. Без интернета."
    val parts = mutableListOf("Всего: ${state.totalCount}")
    if (state.processingCount > 0) parts += "в работе: ${state.processingCount}"
    if (state.queuedCount > 0) parts += "в очереди: ${state.queuedCount}"
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Filters(selected: HomeFilter, onSelect: (HomeFilter) -> Unit) {
    val options = listOf(HomeFilter.ALL to "Все", HomeFilter.DONE to "Готово", HomeFilter.IN_PROGRESS to "В работе")
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (filter, label) ->
            ToggleButton(
                checked = selected == filter,
                onCheckedChange = { onSelect(filter) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(label) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionRow(
    item: SessionItem,
    query: String,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = item.session
    SegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = "Удалить",
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = modifier,
        leadingContent = { StatusAvatar(s.status) },
        trailingContent = if (s.status == SessionStatus.ERROR) {
            {
                FilledTonalIconButton(onClick = onRetry) {
                    Icon(painterResource(R.drawable.ic_redo), contentDescription = "Повторить")
                }
            }
        } else {
            null
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val meta = listOfNotNull(s.durationMs?.let(::formatDuration), formatCreatedAt(s.createdAt))
                Text(meta.joinToString(" · "))
                item.snippet?.let { Snippet(it, query) }
                when (s.status) {
                    SessionStatus.DONE -> SpeakersLine(s.speakerCount)
                    SessionStatus.PROCESSING -> ProgressLine(item)
                    SessionStatus.QUEUED -> Text("В очереди", style = MaterialTheme.typography.labelMedium)
                    SessionStatus.ERROR -> Text(
                        s.errorMessage ?: "Ошибка обработки",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    ) {
        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpeakersLine(count: Int) {
    if (count == 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(count.coerceAtMost(4)) { i ->
            SpeakerShape(speakerStyle(i), Modifier.offset(x = (-4 * i).dp), size = 18.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(pluralSpeakers(count), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressLine(item: SessionItem) {
    val p = item.progress
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
        LinearWavyProgressIndicator(
            progress = { p?.overallFraction ?: 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (p != null) "${(p.overallFraction * 100).toInt()}% · ${stringResource(p.stage.labelRes())}" else "Подготовка…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusAvatar(status: SessionStatus) {
    val cs = MaterialTheme.colorScheme
    val (polygon, bg, fg) = when (status) {
        SessionStatus.DONE -> Triple(MaterialShapes.Cookie9Sided, cs.primaryContainer, cs.onPrimaryContainer)
        SessionStatus.PROCESSING -> Triple(MaterialShapes.Circle, cs.secondaryContainer, cs.onSecondaryContainer)
        SessionStatus.QUEUED -> Triple(MaterialShapes.Pentagon, cs.surfaceContainerHighest, cs.onSurfaceVariant)
        SessionStatus.ERROR -> Triple(MaterialShapes.Square, cs.errorContainer, cs.onErrorContainer)
    }
    Box(
        Modifier.size(48.dp).clip(polygon.toShape()).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            SessionStatus.PROCESSING -> LoadingIndicator(Modifier.size(36.dp))
            else -> Icon(
                painter = painterResource(
                    when (status) {
                        SessionStatus.DONE -> R.drawable.ic_waveform
                        SessionStatus.QUEUED -> R.drawable.ic_clock
                        else -> R.drawable.ic_error
                    },
                ),
                contentDescription = null,
                tint = fg,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(onImport: () -> Unit, onRecord: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(140.dp).clip(MaterialShapes.Cookie12Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_waveform),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp),
            )
        }
        Text("Пока пусто", style = MaterialTheme.typography.headlineSmallEmphasized)
        Text(
            "Импортируйте аудио — расшифровка с разделением по спикерам пройдёт прямо на телефоне.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Button(onClick = onImport, shape = RoundedCornerShape(28.dp), modifier = Modifier.height(56.dp)) {
            Icon(painterResource(R.drawable.ic_upload), contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Импортировать файлы")
        }
        OutlinedButton(onClick = onRecord, shape = RoundedCornerShape(28.dp), modifier = Modifier.height(56.dp)) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Записать с микрофона")
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Row(
            Modifier.height(56.dp).padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = cs.onSurfaceVariant)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Поиск по расшифровкам", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Поиск по расшифровкам" },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Очистить поиск")
                }
            }
        }
    }
}

/** Реплика с подсвеченным совпадением; длинный текст обрезается вокруг него. */
@Composable
private fun Snippet(text: String, query: String) {
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    val from = if (at > 40) text.lastIndexOf(' ', at - 30).coerceAtLeast(0) else 0
    val shown = (if (from > 0) "…" else "") + text.substring(from).trimStart()
    val hit = if (at < 0) -1 else shown.indexOf(q, ignoreCase = true)
    val highlight = SpanStyle(
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        background = MaterialTheme.colorScheme.tertiaryContainer,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = buildAnnotatedString {
            if (hit < 0) {
                append(shown)
            } else {
                append(shown.substring(0, hit))
                withStyle(highlight) { append(shown.substring(hit, hit + q.length)) }
                append(shown.substring(hit + q.length))
            }
        },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
