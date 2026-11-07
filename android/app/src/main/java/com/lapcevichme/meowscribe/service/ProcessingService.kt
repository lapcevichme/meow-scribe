package com.lapcevichme.meowscribe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lapcevichme.meowscribe.MainActivity
import com.lapcevichme.meowscribe.R
import com.lapcevichme.meowscribe.domain.pipeline.Stage
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Держит процесс живым, пока идёт обработка, и показывает прогресс в уведомлении. */
@AndroidEntryPoint
class ProcessingService : LifecycleService() {

    @Inject lateinit var queue: ProcessingQueue

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(buildNotification(null, 0f))

        lifecycleScope.launch {
            queue.progress.collect { map ->
                val p = map.values.firstOrNull()
                notificationManager().notify(NOTIFICATION_ID, buildNotification(p?.stage, p?.overallFraction ?: 0f))
            }
        }
        lifecycleScope.launch {
            queue.busy.first { !it }
            ServiceCompat.stopForeground(this@ProcessingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startInForeground(notification: Notification) {
        // ServiceCompat маскирует незнакомые ему типы (mediaProcessing → none), и на targetSdk 34+ это краш.
        // Поэтому на Q+ вызываем платформенный метод напрямую.
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(stage: Stage?, fraction: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_waveform)
            .setContentTitle(getString(R.string.processing_notification_title))
            .setContentText(stage?.let { stageLabel(it) } ?: getString(R.string.processing_notification_preparing))
            .setProgress(100, (fraction * 100).toInt(), stage == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private fun stageLabel(stage: Stage): String = getString(
        when (stage) {
            Stage.DECODE -> R.string.stage_decode
            Stage.VAD -> R.string.stage_vad
            Stage.DIARIZATION -> R.string.stage_diarization
            Stage.ASR -> R.string.stage_asr
            Stage.MERGE -> R.string.stage_merge
            Stage.LLM -> R.string.stage_llm
        },
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.processing_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
            }
        }
    }
}
