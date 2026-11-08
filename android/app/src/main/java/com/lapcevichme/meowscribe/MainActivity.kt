package com.lapcevichme.meowscribe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.lapcevichme.meowscribe.domain.processing.ProcessingQueue
import com.lapcevichme.meowscribe.domain.repository.SettingsRepository
import com.lapcevichme.meowscribe.domain.usecase.ImportAudioUseCase
import com.lapcevichme.meowscribe.presentation.navigation.MeowNavHost
import com.lapcevichme.meowscribe.presentation.theme.MeowScribeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var importAudio: ImportAudioUseCase
    @Inject lateinit var queue: ProcessingQueue
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val wallpaperColors by settings.wallpaperColors.collectAsStateWithLifecycle()
            MeowScribeTheme(dynamicColor = wallpaperColors) {
                // Surface задаёт onSurface как LocalContentColor, иначе Text без цвета рисуется чёрным.
                Surface(Modifier.fillMaxSize()) {
                    MeowNavHost()
                }
            }
        }
        if (savedInstanceState == null) {
            // Сессии, прерванные убийством процесса, ставим обратно в очередь.
            lifecycleScope.launch { queue.resumePending() }
            handleIncoming(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** «Поделиться» / «Открыть с помощью» из других приложений. */
    private fun handleIncoming(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val result = importAudio(uris.map { it.toString() })
            val text = when {
                result.failed == 0 -> "Добавлено в очередь: ${result.imported.size}"
                result.imported.isEmpty() -> "Не удалось открыть файл"
                else -> "Добавлено: ${result.imported.size}, не удалось: ${result.failed}"
            }
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
        }
    }
}
