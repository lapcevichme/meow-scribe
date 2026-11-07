package com.lapcevichme.meowscribe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lapcevichme.meowscribe.data.models.ModelLocator
import com.lapcevichme.meowscribe.data.pipeline.sherpa.SherpaPipeline
import com.lapcevichme.meowscribe.domain.model.PipelineConfig
import com.lapcevichme.meowscribe.domain.model.Utterance
import com.lapcevichme.meowscribe.domain.pipeline.PipelineEvent
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Полный SherpaPipeline на записях с 2–4 говорящими, в том числе с испорченным звуком
 * (дальний микрофон, дешёвый микрофон с перегрузом, гул голосов, тихая запись).
 * Файлы и эталоны кладёт `python bench/export_android_tests.py` в androidTest/assets/multi/.
 * Печатает WER, DER (без коллара) и число спикеров; падает, если результат явно сломан.
 *
 *   adb shell am instrument -w -e class com.lapcevichme.meowscribe.MultiSpeakerTest \
 *     com.lapcevichme.meowscribe.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -s MeowMulti
 */
@RunWith(AndroidJUnit4::class)
class MultiSpeakerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private val pipeline = SherpaPipeline(ModelLocator(instrumentation.targetContext))

    @Test
    fun multiSpeaker() = runBlocking {
        val names = assets.list(DIR)?.filter { it.endsWith(".wav") }.orEmpty().sorted()
        assumeTrue("нет файлов в androidTest/assets/$DIR", names.isNotEmpty())
        val failures = mutableListOf<String>()
        for (name in names) {
            val pcm = readWav("$DIR/$name")
            val ref = readRef("$DIR/${name.removeSuffix(".wav")}.json")
            val done = pipeline.run(pcm, PipelineConfig(language = "auto", useLlm = false))
                .filterIsInstance<PipelineEvent.Done>().first()
            val hyp = done.utterances
            val wer = wer(ref, hyp)
            val der = der(ref, hyp)
            val nRef = ref.map { it.speakerId }.toSet().size
            val nHyp = hyp.map { it.speakerId }.toSet().size
            val line = "%-28s wer %.3f der %.3f spk %d/%d rtf %.3f peak %s МБ".format(
                name, wer, der, nHyp, nRef, done.stats.rtf, done.stats.peakRamMb)
            Log.i(TAG, line)
            println(line)
            // пороги с запасом над цифрами стенда: тест ловит поломку, а не мерит качество (качество — в bench/)
            val hard = HARD.any { name.contains(it) }
            if (wer > if (hard) 0.8 else 0.25) failures += "$name: WER $wer"
            if (der > if (hard) 0.75 else 0.4) failures += "$name: DER $der"
            if (nHyp == 0) failures += "$name: нет спикеров"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ---------------------------------------------------------------- метрики

    private fun words(text: String) = text.lowercase().replace('ё', 'е')
        .replace(Regex("(\\w)-(\\w)"), "$1$2")
        .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
        .split(' ').filter { it.isNotBlank() }

    private fun wer(ref: List<Utterance>, hyp: List<Utterance>): Double {
        val r = words(ref.sortedBy { it.startMs }.joinToString(" ") { it.text })
        val h = words(hyp.sortedBy { it.startMs }.joinToString(" ") { it.text })
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[h.size].toDouble() / maxOf(r.size, 1)
    }

    /** DER по кадрам 10 мс, без коллара, с перекрытиями; лучшее сопоставление спикеров перебором. */
    private fun der(ref: List<Utterance>, hyp: List<Utterance>): Double {
        val n = (maxOf(ref.maxOfOrNull { it.endMs } ?: 0, hyp.maxOfOrNull { it.endMs } ?: 0) / 10 + 1).toInt()
        fun frames(us: List<Utterance>): Pair<List<String>, Array<BooleanArray>> {
            val ids = us.map { it.speakerId }.distinct()
            val m = Array(ids.size) { BooleanArray(n) }
            us.forEach { u -> for (f in (u.startMs / 10).toInt() until (u.endMs / 10).toInt()) m[ids.indexOf(u.speakerId)][f] = true }
            return ids to m
        }
        val (_, r) = frames(ref)
        val (_, h) = frames(hyp)
        val overlap = Array(r.size) { i -> IntArray(h.size) { j -> (0 until n).count { r[i][it] && h[j][it] } } }
        var best = 0
        fun search(i: Int, used: Set<Int>, acc: Int) {
            if (i == r.size) { best = maxOf(best, acc); return }
            search(i + 1, used, acc)
            for (j in h.indices) if (j !in used) search(i + 1, used + j, acc + overlap[i][j])
        }
        search(0, emptySet(), 0)
        var refSpeech = 0
        var errors = 0
        for (f in 0 until n) {
            val nr = r.count { it[f] }
            val nh = h.count { it[f] }
            refSpeech += nr
            errors += maxOf(nr, nh)
        }
        // max(nr, nh) по кадрам − верно сопоставленные = промахи + ложные + путаница
        return (errors - best).toDouble() / maxOf(refSpeech, 1)
    }

    // ---------------------------------------------------------------- файлы

    private fun readRef(path: String): List<Utterance> {
        val arr = JSONObject(assets.open(path).bufferedReader().readText()).getJSONArray("transcript")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Utterance(o.getString("speaker_id"), o.getLong("start_ms"), o.getLong("end_ms"), o.getString("text"))
        }
    }

    private fun readWav(path: String): FloatArray {
        val bytes = assets.open(path).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = buf.getInt(pos + 4)
            if (String(bytes, pos, 4) == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                return FloatArray(n) { i -> buf.getShort(pos + 8 + i * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("В $path нет data-чанка")
    }

    private companion object {
        const val TAG = "MeowMulti"
        const val DIR = "multi"
        /** испорченный звук и живые совещания AMI (англ., разговорная речь с «um/uh» в эталоне) */
        val HARD = listOf("_far", "_cheap", "_babble", "_quiet", "en_ami")
    }
}
