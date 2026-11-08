@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.lapcevichme.meowscribe.presentation.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lapcevichme.meowscribe.R
import com.lapcevichme.meowscribe.domain.model.Profile
import com.lapcevichme.meowscribe.domain.voice.VoiceProfile
import com.lapcevichme.meowscribe.presentation.components.BackTopBar
import com.lapcevichme.meowscribe.presentation.components.label
import com.lapcevichme.meowscribe.presentation.components.ChoiceGroup
import com.lapcevichme.meowscribe.presentation.components.languageLabel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val voiceProfiles by viewModel.voiceProfiles.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Настройки", style = MaterialTheme.typography.displaySmallEmphasized, modifier = Modifier.padding(start = 4.dp))
            Text(
                "Применяются к новым записям. Уже обработанные не меняются.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )

            Section("Режим распознавания") {
                ChoiceGroup(
                    options = Profile.entries,
                    selected = config.profile,
                    label = { it.label() },
                    onSelect = viewModel::setProfile,
                )
                Hint(
                    when (config.profile) {
                        Profile.FAST -> "Говорящих разделяет вдвое быстрее, но чуть чаще путает. Текст распознаётся так же."
                        Profile.ACCURATE -> "Точнее разделяет говорящих, обработка немного дольше. Текст распознаётся так же."
                    },
                )
            }

            Section("Язык записи") {
                ChoiceGroup(
                    options = listOf("ru", "en", "auto"),
                    selected = config.language,
                    label = ::languageLabel,
                    onSelect = viewModel::setLanguage,
                )
            }

            Section("Сколько говорящих") {
                ChoiceGroup(
                    options = listOf<Int?>(null, 1, 2, 3, 4, 5),
                    selected = config.numSpeakers,
                    label = { it?.toString() ?: "Авто" },
                    onSelect = viewModel::setSpeakers,
                    weight = { if (it == null) 2f else 1f },
                )
                Hint("Если число известно заранее, разделение по спикерам будет точнее.")
            }

            Section("Языковая модель") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Название и сводка", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Локальная LLM придумывает название и выписывает главное. Текст расшифровки она не меняет.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.useLlm,
                        onCheckedChange = viewModel::setLlm,
                        thumbContent = if (config.useLlm) {
                            { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(SwitchDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }

            Section("Голоса") {
                if (voiceProfiles.isEmpty()) {
                    Hint("Пока пусто. В расшифровке нажмите на спикера, введите имя и отметьте «Запомнить голос» — в следующих записях приложение подпишет его само.")
                } else {
                    Hint("Узнаём этих людей в новых записях. Нажмите на голос, чтобы послушать. Голоса хранятся только на телефоне.")
                    voiceProfiles.forEach { profile ->
                        VoiceRow(
                            profile = profile,
                            preview = preview?.takeIf { it.profileId == profile.id },
                            onPlay = { viewModel.togglePreview(profile.id) },
                            onDelete = { viewModel.deleteVoice(profile.id) },
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_phone), contentDescription = null)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Всё на устройстве", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Модели встроены в приложение. Интернет не нужен, аудио никуда не отправляется.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(
                "MeowScribe ${version.orEmpty()} · chupapis",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            content()
        }
    }
}

@Composable
private fun VoiceRow(profile: VoiceProfile, preview: VoicePreview?, onPlay: () -> Unit, onDelete: () -> Unit) {
    val active = preview != null && !preview.missing
    Surface(
        onClick = onPlay,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column {
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        preview != null && !preview.missing && !preview.playing -> LoadingIndicator(Modifier.size(24.dp))
                        else -> Icon(
                            painterResource(if (active) R.drawable.ic_stop else R.drawable.ic_play),
                            contentDescription = if (active) "Остановить" else "Послушать голос «${profile.name}»",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralRecordings(profile.samples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Удалить голос «${profile.name}»")
                }
            }
            preview?.text?.let { text ->
                Text(
                    if (preview.missing) text else "«$text»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
}

private fun pluralRecordings(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "записей"
        n % 10 == 1 -> "запись"
        n % 10 in 2..4 -> "записи"
        else -> "записей"
    }
    return "$n $word"
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

