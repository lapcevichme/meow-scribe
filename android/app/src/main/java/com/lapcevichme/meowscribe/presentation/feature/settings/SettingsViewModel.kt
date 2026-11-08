package com.lapcevichme.meowscribe.presentation.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Profile
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.domain.repository.SessionRepository
import com.lapcevichme.meowscribe.domain.repository.SettingsRepository
import com.lapcevichme.meowscribe.domain.repository.VoiceProfileRepository
import com.lapcevichme.meowscribe.domain.voice.VoiceProfile
import com.lapcevichme.meowscribe.presentation.components.displayTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Запись, где встречается сохранённый голос. */
data class VoiceAppearance(val sessionId: Long, val title: String, val createdAt: Long, val durationMs: Long?)

/** Что сейчас звучит в списке голосов. [text] — реплика из записи или пояснение, почему играть нечего. */
data class VoicePreview(val profileId: Long, val text: String?, val playing: Boolean, val missing: Boolean = false)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val voices: VoiceProfileRepository,
    private val sessions: SessionRepository,
) : ViewModel() {

    val config: StateFlow<PipelineConfig> = settings.config
    val wallpaperColors: StateFlow<Boolean> = settings.wallpaperColors

    val voiceProfiles: StateFlow<List<VoiceProfile>> = voices.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _preview = MutableStateFlow<VoicePreview?>(null)
    val preview: StateFlow<VoicePreview?> = _preview.asStateFlow()

    /** Голос, у которого раскрыт список записей, и сами записи (null — ещё грузятся). */
    private val _expanded = MutableStateFlow<Pair<Long, List<VoiceAppearance>?>?>(null)
    val expanded: StateFlow<Pair<Long, List<VoiceAppearance>?>?> = _expanded.asStateFlow()

    fun toggleExpanded(profileId: Long) {
        if (_expanded.value?.first == profileId) {
            _expanded.value = null
            return
        }
        _expanded.value = profileId to null
        viewModelScope.launch {
            val list = voices.appearances(profileId)
                .map { it.first }
                .distinct()
                .mapNotNull { id -> sessions.getSession(id)?.takeIf { it.status == SessionStatus.DONE } }
                .map { VoiceAppearance(it.id, it.displayTitle, it.createdAt, it.durationMs) }
            if (_expanded.value?.first == profileId) _expanded.value = profileId to list
        }
    }

    private var player: ExoPlayer? = null
    private var loading: Job? = null

    fun deleteVoice(id: Long) {
        if (_preview.value?.profileId == id) stopPreview()
        if (_expanded.value?.first == id) _expanded.value = null
        viewModelScope.launch { voices.delete(id) }
    }

    /**
     * Проигрывает голос: берём самую новую запись, где он встречался, и самую длинную реплику
     * этого спикера в ней (не длиннее [MAX_CLIP_MS]). Повторное нажатие останавливает.
     */
    fun togglePreview(profileId: Long) {
        if (_preview.value?.profileId == profileId) {
            stopPreview()
            return
        }
        stopPreview()
        _preview.value = VoicePreview(profileId, text = null, playing = false)
        loading = viewModelScope.launch {
            val clip = findClip(profileId)
            if (clip == null) {
                _preview.value = VoicePreview(profileId, "Записи с этим голосом удалены — послушать не получится", playing = false, missing = true)
                return@launch
            }
            val exo = player ?: createPlayer().also { player = it }
            exo.setMediaItem(
                MediaItem.Builder()
                    .setUri(clip.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startMs)
                            .setEndPositionMs(clip.endMs)
                            .build(),
                    )
                    .build(),
            )
            exo.prepare()
            exo.play()
            _preview.value = VoicePreview(profileId, clip.text, playing = true)
        }
    }

    fun stopPreview() {
        loading?.cancel()
        player?.stop()
        _preview.value = null
    }

    private data class Clip(val uri: String, val startMs: Long, val endMs: Long, val text: String)

    private suspend fun findClip(profileId: Long): Clip? {
        for ((sessionId, speakerId) in voices.appearances(profileId)) {
            val session = sessions.getSession(sessionId) ?: continue
            if (session.status != SessionStatus.DONE) continue
            val best = sessions.getTranscript(sessionId)
                .filter { it.speakerId == speakerId }
                .maxByOrNull { minOf(it.endMs - it.startMs, MAX_CLIP_MS) }
                ?: continue
            return Clip(session.audioUri, best.startMs, minOf(best.endMs, best.startMs + MAX_CLIP_MS), best.text)
        }
        return null
    }

    private fun createPlayer() = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) _preview.value = null
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = _preview.value?.profileId ?: return
                _preview.value = VoicePreview(id, "Не удалось открыть аудио — возможно, файл удалён", playing = false, missing = true)
            }
        })
    }

    override fun onCleared() {
        player?.release()
        player = null
    }

    fun setProfile(value: Profile) = settings.update { it.copy(profile = value) }
    fun setLanguage(value: String) = settings.update { it.copy(language = value) }
    fun setSpeakers(value: Int?) = settings.update { it.copy(numSpeakers = value) }
    fun setLlm(value: Boolean) = settings.update { it.copy(useLlm = value) }
    fun setWallpaperColors(value: Boolean) = settings.setWallpaperColors(value)

    private companion object {
        const val MAX_CLIP_MS = 15_000L
    }
}
