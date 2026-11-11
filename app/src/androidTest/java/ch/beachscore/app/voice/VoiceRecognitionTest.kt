package ch.beachscore.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.Team
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Real audio in → command out. Speech WAVs go through the real Vosk recogniser with the
 * bundled models, the production grammar and [CommandParser] (via [CommandRecognizer]):
 *
 *  - English fixtures made with four Windows SAPI voices (assets/voice, en__<expected>__…),
 *  - German and English phrases synthesised on the device with Android TTS,
 *  - each also 20 dB quieter with background noise, as a crude stand-in for distance and wind.
 *
 * Clean audio must give exactly the expected command (or none for non-commands) at NORMAL
 * sensitivity. Degraded audio may be missed but must never give a wrong command, even at RELAXED. A results table with confidences is
 * written to logcat (tag VoiceRecognitionTest) and to files/voice-recognition-report.tsv.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecognitionTest {

    private data class Sample(val name: String, val language: Language, val expected: ScoreCommand?, val pcm: ShortArray)

    private data class Outcome(val sample: String, val variant: String, val expected: ScoreCommand?, val heard: List<Heard>, val json: List<String>) {
        val accepted get() = heard.mapNotNull { (it.verdict as? Verdict.Accepted)?.command }
    }

    @Test
    fun cleanCommandsAreRecognisedAndNonCommandsAreNot() {
        val failures = mutableListOf<String>()
        for (sample in samples) {
            val outcome = run(sample, "clean", sample.pcm)
            val ok = if (sample.expected == null) outcome.accepted.isEmpty() else outcome.accepted == listOf(sample.expected)
            if (!ok) failures += "${sample.name}: expected ${sample.expected}, got ${outcome.heard}"
        }
        failures.forEach { Log.e(TAG, "FAIL $it") }
        assertTrue("clean audio misrecognised:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun degradedAudioNeverGivesAWrongCommand() {
        val wrong = mutableListOf<String>()
        var positives = 0
        var hits = 0
        for (sample in samples) {
            val degraded = degrade(sample.pcm, gain = 0.1, noiseRms = 120.0, seed = sample.name.hashCode().toLong())
            // At the most permissive level: if a wrong command can slip through anywhere, it is here.
            val outcome = run(sample, "quiet+noise", degraded, VoiceSensitivity.RELAXED)
            if (sample.expected != null) {
                positives++
                if (outcome.accepted == listOf(sample.expected)) hits++
            }
            if (outcome.accepted.any { it != sample.expected }) wrong += "${sample.name}: expected ${sample.expected}, got ${outcome.heard}"
        }
        Log.i(TAG, "quiet+noise: $hits of $positives commands recognised")
        report += "# quiet+noise: $hits of $positives commands recognised"
        assertTrue("wrong commands from degraded audio:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    private fun run(sample: Sample, variant: String, pcm: ShortArray, sensitivity: VoiceSensitivity = VoiceSensitivity.NORMAL): Outcome {
        val heard = mutableListOf<Heard>()
        val json = mutableListOf<String>()
        CommandRecognizer(models.getValue(sample.language), sample.language, sensitivity.minConfidence).use { rec ->
            val chunk = CommandRecognizer.SAMPLE_RATE / 10
            val padded = ShortArray(chunk * 3) + pcm + ShortArray(CommandRecognizer.SAMPLE_RATE) // silence around the phrase
            var i = 0
            while (i < padded.size) {
                val n = minOf(chunk, padded.size - i)
                val part = padded.copyOfRange(i, i + n)
                rec.accept(part, n)?.let { heard += it; json += rec.lastJson.orEmpty() }
                i += n
            }
            rec.finish()?.let { heard += it; json += rec.lastJson.orEmpty() }
        }
        val outcome = Outcome(sample.name, variant, sample.expected, heard, json)
        val line = listOf(
            variant, sample.name, sample.expected?.toString() ?: "none",
            heard.joinToString(" | ") { "\"${it.text}\" min=${"%.2f".format(Locale.ROOT, it.minConfidence ?: -1.0)} ${it.verdict}" },
        ).joinToString("\t")
        Log.i(TAG, line)
        report += line
        if (variant == "clean" && sample.name == "de__tts__undo") Log.i(TAG, "raw json: ${json.joinToString()}")
        return outcome
    }

    companion object {
        private const val TAG = "VoiceRecognitionTest"
        private val models = mutableMapOf<Language, Model>()
        private val samples = mutableListOf<Sample>()
        private val report = mutableListOf<String>()

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val testContext = InstrumentationRegistry.getInstrumentation().context
            for (language in Language.entries) {
                models[language] = Model(ModelStore(target).ensure(language).absolutePath)
            }
            // SAPI fixtures (English).
            val assets = testContext.assets
            for (name in assets.list("voice").orEmpty().sorted()) {
                val (lang, expected) = name.split("__")
                val pcm = assets.open("voice/$name").use { readWav(it.readBytes()) }
                samples += Sample(name.removeSuffix(".wav"), Language.fromTag(lang)!!, command(expected), pcm)
            }
            // Android TTS, German and English.
            val tts = synthesize(
                target,
                listOf(
                    Triple(Language.DE, "point_blue", "Punkt Blau"),
                    Triple(Language.DE, "point_red", "Punkt Rot"),
                    Triple(Language.DE, "undo", "zurück"),
                    Triple(Language.DE, "none", "fünf, drei"),
                    Triple(Language.DE, "none", "Satz Blau, fünfzehn, zwölf"),
                    Triple(Language.DE, "none", "Korrektur, vier, drei"),
                    Triple(Language.DE, "none", "Punkt Grün"),
                    Triple(Language.DE, "none", "Der Ball war aus"),
                    Triple(Language.DE, "none", "Punkt"),
                    Triple(Language.DE, "none", "Rot"),
                    Triple(Language.DE, "point_blue", "Blau Punkt"),
                    Triple(Language.DE, "point_red", "Rot Punkt"),
                    Triple(Language.EN, "point_red", "red point"),
                    Triple(Language.EN, "point_blue", "point blue"),
                    Triple(Language.EN, "point_red", "point red"),
                    Triple(Language.EN, "undo", "undo"),
                    Triple(Language.EN, "none", "Set Red, twenty-one, nineteen"),
                    Triple(Language.EN, "none", "out"),
                ),
            )
            samples += tts
            Log.i(TAG, "${samples.size} samples (${tts.size} from Android TTS)")
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            models.values.forEach { it.close() }
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            File(target.getExternalFilesDir(null), "voice-recognition-report.tsv").writeText(report.joinToString("\n"))
        }

        private fun command(expected: String): ScoreCommand? = when (expected) {
            "point_blue" -> ScoreCommand.TeamPoint(Team.BLUE)
            "point_red" -> ScoreCommand.TeamPoint(Team.RED)
            "undo" -> ScoreCommand.Undo
            else -> null
        }

        private fun synthesize(context: Context, phrases: List<Triple<Language, String, String>>): List<Sample> {
            val ready = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            val tts = TextToSpeech(context) { status = it; ready.countDown() }
            check(ready.await(20, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS) { "TTS init failed" }
            val dir = File(context.cacheDir, "tts-wav").apply { mkdirs() }
            val out = mutableListOf<Sample>()
            try {
                for ((i, phrase) in phrases.withIndex()) {
                    val (language, expected, text) = phrase
                    val available = tts.setLanguage(Locale.forLanguageTag(language.tag))
                    if (available < TextToSpeech.LANG_AVAILABLE) {
                        Log.w(TAG, "no TTS voice for $language, skipping \"$text\"")
                        continue
                    }
                    val file = File(dir, "$i.wav")
                    val done = CountDownLatch(1)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit
                        override fun onDone(utteranceId: String) = done.countDown()
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String) = done.countDown()
                    })
                    tts.synthesizeToFile(text, null, file, "u$i")
                    check(done.await(20, TimeUnit.SECONDS)) { "TTS timed out for $text" }
                    val slug = text.lowercase().replace(Regex("[^a-zäöüß]+"), "_").trim('_')
                    out += Sample("${language.tag}__tts__${if (expected == "none") slug else expected}", language, command(expected), readWav(file.readBytes()))
                }
            } finally {
                tts.shutdown()
            }
            return out
        }

        /** 16-bit PCM WAV (mono or first channel of stereo), resampled to 16 kHz. */
        fun readWav(bytes: ByteArray): ShortArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
            var pos = 12
            var rate = 0
            var channels = 1
            var bits = 16
            var data: ShortArray? = null
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = buf.getInt(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        channels = buf.getShort(body + 2).toInt()
                        rate = buf.getInt(body + 4)
                        bits = buf.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        check(bits == 16) { "only 16-bit PCM" }
                        val len = minOf(size, bytes.size - body)
                        val frames = len / (2 * channels)
                        data = ShortArray(frames) { buf.getShort(body + it * 2 * channels) }
                    }
                }
                pos = body + size + (size and 1)
            }
            return resample(checkNotNull(data) { "no data chunk" }, rate, CommandRecognizer.SAMPLE_RATE)
        }

        private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
            if (from == to) return input
            // Box low-pass over the decimation ratio, then linear interpolation.
            val ratio = from.toDouble() / to
            val width = maxOf(1, ratio.toInt())
            val smoothed = DoubleArray(input.size) { i ->
                var sum = 0.0
                for (k in 0 until width) sum += input[minOf(i + k, input.size - 1)]
                sum / width
            }
            return ShortArray((input.size / ratio).toInt()) { i ->
                val x = i * ratio
                val j = x.toInt()
                val f = x - j
                val a = smoothed[j]
                val b = smoothed[minOf(j + 1, smoothed.size - 1)]
                (a + (b - a) * f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        /** Scales the speech by [gain], pads 0.5 s each side and adds white noise of [noiseRms] throughout. */
        fun degrade(pcm: ShortArray, gain: Double, noiseRms: Double, seed: Long): ShortArray {
            val random = Random(seed)
            val pad = CommandRecognizer.SAMPLE_RATE / 2
            return ShortArray(pcm.size + 2 * pad) { i ->
                val speech = if (i in pad until pad + pcm.size) pcm[i - pad] * gain else 0.0
                (speech + random.nextGaussian() * noiseRms).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        @Suppress("unused")
        private fun rms(pcm: ShortArray) = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
    }
}
