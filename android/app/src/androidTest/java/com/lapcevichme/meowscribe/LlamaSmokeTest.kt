package com.lapcevichme.meowscribe

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lapcevichme.meowscribe.llama.LlamaModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Проверка llama.cpp на устройстве: нативная библиотека грузится, модель отвечает, замеры скорости и RAM.
 * GGUF не кладём в APK ради теста — подкладываем через adb:
 *
 *   adb push model.gguf /data/local/tmp/smoke.gguf
 *   adb shell am instrument -w -e class com.lapcevichme.meowscribe.LlamaSmokeTest \
 *       com.lapcevichme.meowscribe.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LlamaSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun punctuation() {
        val path = InstrumentationRegistry.getArguments().getString("gguf") ?: "/data/local/tmp/smoke.gguf"
        val source = File(path)
        assumeTrue("нет $path", source.canRead())
        // Копируем в filesDir, как в приложении: так же, как работает ModelLocator.llmFile().
        val file = File(instrumentation.targetContext.filesDir, "smoke.gguf")
        if (file.length() != source.length()) source.copyTo(file, overwrite = true)

        report("system: ${LlamaModel.systemInfo()}")
        val rssBefore = rssMb()
        val start = SystemClock.elapsedRealtime()
        LlamaModel.load(file.path, contextSize = 1024).use { llm ->
            val loadMs = SystemClock.elapsedRealtime() - start
            report("load $loadMs мс, RSS $rssBefore → ${rssMb()} МБ, ctx ${llm.contextSize}")

            val input = "привет как дела я сегодня был в магазине и купил молоко хлеб и сыр а ты что делал"
            val prompt = llm.chatPrompt(
                system = "Расставь знаки препинания и заглавные буквы. Не меняй, не добавляй и не удаляй слова. " +
                    "Ответь только исправленным текстом.",
                user = input,
            )
            val promptTokens = llm.countTokens(prompt)
            val t0 = SystemClock.elapsedRealtime()
            val answer = llm.generate(prompt, maxTokens = 96)
            val genMs = SystemClock.elapsedRealtime() - t0
            val outTokens = llm.countTokens(answer)
            report("prompt $promptTokens ток., ответ $outTokens ток. за $genMs мс (${"%.1f".format(outTokens * 1000f / genMs)} ток/с), RSS ${rssMb()} МБ")
            report("ответ: «$answer»")
            assertTrue(answer.isNotBlank())

            // Отмена должна прерывать генерацию сразу.
            var calls = 0
            llm.generate(prompt, maxTokens = 96, cancellation = { ++calls > 3 })
            report("отмена после $calls проверок")
        }
    }

    private fun rssMb(): Long = File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() / 1024
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $line\n") })
    }

    private companion object {
        const val TAG = "MeowLlamaSmoke"
    }
}
