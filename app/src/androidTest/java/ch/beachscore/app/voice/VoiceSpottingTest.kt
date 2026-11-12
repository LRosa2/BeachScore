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
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Commands without silence around them, as at a beach court: real Vosk, bundled models,
 * production grammar, [CommandRecognizer] and [CommandParser], fed in 100 ms pieces like
 * the microphone.
 *
 * Each command clip (Android TTS in three voice variants per language, plus the English SAPI
 * fixtures) is trimmed to its speech and placed
 *  - `speech_around`: directly between two neutral sentences, no gap;
 *  - `noise_around`: directly between two loud noise bursts;
 *  - `noise_bed`: under continuous noise and background chatter that never stops;
 *  - `speech+bed`: both.
 * Negatives are long stretches of chatter that contain the command words separately and
 * not adjacent (and the app's own announcements), with and without the noise bed.
 *
 * Reported (logcat tag VoiceSpottingTest and files/voice-spotting-report.tsv): hits per
 * condition, wrong commands, false triggers, and the time from the end of the command to the
 * moment it was accepted.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSpottingTest {

    private class Clip(val name: String, val language: Language, val expected: ScoreCommand?, val pcm: ShortArray)

    private class Run(val accepted: List<ScoreCommand>, val heard: List<Heard>, val acceptedAt: List<Int>)

    @Test
    fun commandsAreSpottedWithoutSilenceAroundThem() {
        val summary = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        var totalHits = 0
        var total = 0
        val allLatencies = mutableListOf<Int>()
        for (condition in CONDITIONS) {
            var hits = 0
            var n = 0
            var late = 0
            val latencies = mutableListOf<Int>()
            for (clip in commands) {
                val (pcm, commandEnd) = place(clip, condition)
                val run = run(clip.language, pcm, VoiceSensitivity.NORMAL)
                n++
                val hit = run.accepted == listOf(clip.expected)
                if (hit) {
                    hits++
                    val ms = (run.acceptedAt.first() - commandEnd) * 1000 / RATE
                    latencies += ms
                    if (ms > 1000) late++
                }
                if (run.accepted.any { it != clip.expected }) wrong += "$condition ${clip.name}: ${run.heard}"
                line("$condition\t${clip.name}\t${clip.expected}\t${if (hit) "HIT" else "miss"}\t" +
                    (if (hit) "${(run.acceptedAt.first() - commandEnd) * 1000 / RATE} ms" else "-") + "\t" + describe(run.heard))
            }
            totalHits += hits
            total += n
            allLatencies += latencies
            summary += "$condition: $hits of $n recognised, $late of them later than 1 s; latency ${stats(latencies)}"
        }
        summary += "all conditions: $totalHits of $total recognised; latency ${stats(allLatencies)}; wrong commands: ${wrong.size}"
        summary.forEach { line("# $it") }
        wrong.forEach { line("WRONG $it") }
        assertTrue("wrong commands:\n" + wrong.joinToString("\n"), wrong.isEmpty())
        assertTrue("too few commands recognised:\n" + summary.joinToString("\n"), totalHits >= total * MIN_HIT_RATE)
    }

    @Test
    fun chatterWithCommandWordsDoesNotTrigger() {
        val triggers = mutableListOf<String>()
        val summary = mutableListOf<String>()
        var worst = 0
        for (sensitivity in listOf(VoiceSensitivity.NORMAL, VoiceSensitivity.RELAXED)) {
            var n = 0
            var count = 0
            for (stream in chatterStreams) for (bed in listOf(false, true)) {
                val pcm = if (bed) withBed(stream.pcm, stream.language, stream.name.hashCode().toLong()) else stream.pcm
                val run = run(stream.language, pcm + ShortArray(RATE), sensitivity)
                n++
                val label = "chatter${if (bed) "+bed" else ""} $sensitivity"
                line("$label\t${stream.name}\t-\t${if (run.accepted.isEmpty()) "ok" else "TRIGGER ${run.accepted}"}\t\t${describe(run.heard)}")
                if (run.accepted.isNotEmpty()) {
                    count++
                    triggers += "$label ${stream.name}: ${run.heard}"
                }
            }
            summary += "chatter at $sensitivity: $count of $n streams gave a command"
            worst = maxOf(worst, count)
        }
        summary.forEach { line("# $it") }
        assertTrue("too many false triggers:\n" + triggers.joinToString("\n"), worst <= MAX_CHATTER_TRIGGERS)
    }

    /**
     * 3.3.0: a replaced word set ([CUSTOM], German). Its own commands must be spotted like the
     * defaults, and the old default commands must no longer score. Measured twice: with the
     * replaced default words as decoys in the grammar (production) and without them.
     */
    @Test
    fun customWordsReplaceTheDefaults() {
        for (word in CUSTOM.all) assertTrue("model lacks $word", VoskVocabulary.knows(models.getValue(Language.DE), word))
        val summary = mutableListOf<String>()
        var productionOldScored = -1
        var productionWrong = -1
        for ((label, words) in listOf("decoys" to CUSTOM, "no-decoys" to CUSTOM.copy(replaced = emptyList()))) {
            var hits = 0
            var n = 0
            val wrong = mutableListOf<String>()
            val latencies = mutableListOf<Int>()
            for (condition in CONDITIONS) for (clip in customCommands) {
                val (pcm, end) = place(clip, condition)
                val run = run(words, pcm, VoiceSensitivity.NORMAL)
                n++
                val hit = run.accepted == listOf(clip.expected)
                if (hit) {
                    hits++
                    latencies += (run.acceptedAt.first() - end) * 1000 / RATE
                }
                if (run.accepted.any { it != clip.expected }) wrong += "$condition ${clip.name}: ${run.heard}"
                line("custom-$label	$condition	${clip.name}	${if (hit) "HIT" else "miss"}	${describe(run.heard)}")
            }
            var oldScored = 0
            var oldN = 0
            val oldPoints = commands.filter { it.language == Language.DE && it.expected is ScoreCommand.TeamPoint }
            for (condition in CONDITIONS) for (clip in oldPoints) {
                val run = run(words, place(clip, condition).first, VoiceSensitivity.RELAXED)
                oldN++
                val scored = run.accepted.any { it is ScoreCommand.TeamPoint }
                if (scored) oldScored++
                line("old-default-$label	$condition	${clip.name}	${if (scored) "SCORED ${run.accepted}" else "ok"}	${describe(run.heard)}")
            }
            var triggers = 0
            var streams = 0
            for (stream in chatterStreams.filter { it.language == Language.DE }) for (bed in listOf(false, true)) {
                val pcm = if (bed) withBed(stream.pcm, stream.language, stream.name.hashCode().toLong()) else stream.pcm
                val run = run(words, pcm + ShortArray(RATE), VoiceSensitivity.NORMAL)
                streams++
                if (run.accepted.isNotEmpty()) triggers++
                line("chatter-$label	${stream.name}	$bed	${if (run.accepted.isEmpty()) "ok" else "TRIGGER ${run.accepted}"}	${describe(run.heard)}")
            }
            summary += "custom words ($label): $hits of $n recognised, latency ${stats(latencies)}, wrong ${wrong.size}; " +
                "old default point commands scoring (RELAXED): $oldScored of $oldN; German chatter giving a command: $triggers of $streams"
            wrong.forEach { line("WRONG custom-$label $it") }
            if (words == CUSTOM) {
                productionOldScored = oldScored
                productionWrong = wrong.size
            }
        }
        summary.forEach { line("# $it") }
        assertTrue(summary.joinToString("\n"), productionOldScored == 0 && productionWrong == 0)
    }

    /**
     * 3.3.0: "punk"/"points" as default point words. Spoken variant commands ("Punk Blau",
     * "red points") and all regular commands, with the production grammar (variants heard
     * through "punkt"/"point") and with the variants as grammar entries of their own.
     */
    @Test
    fun alikePointVariants() {
        val summary = mutableListOf<String>()
        for (mode in listOf("absorbed", "separate")) {
            fun words(language: Language) = CommandWords.defaults(language).let { if (mode == "separate") it.copy(alike = emptySet()) else it }
            for ((label, clips) in listOf("variant commands" to variantCommands, "regular commands" to commands)) {
                if (mode == "absorbed" && label == "regular commands") continue // commandsAreSpottedWithoutSilenceAroundThem
                var hits = 0
                var n = 0
                var wrong = 0
                for (condition in CONDITIONS) for (clip in clips) {
                    val run = run(words(clip.language), place(clip, condition).first, VoiceSensitivity.NORMAL)
                    n++
                    val hit = run.accepted == listOf(clip.expected)
                    if (hit) hits++
                    if (run.accepted.any { it != clip.expected }) wrong++
                    line("alike-$mode	$condition	${clip.name}	${if (hit) "HIT" else "miss"}	${describe(run.heard)}")
                }
                summary += "point variants $mode, $label: $hits of $n recognised, wrong $wrong"
            }
        }
        summary.forEach { line("# $it") }
    }

    @Test
    fun theModelVocabularyIsReadable() {
        val de = models.getValue(Language.DE)
        val en = models.getValue(Language.EN)
        assertTrue(VoskVocabulary.knows(de, "blau") && VoskVocabulary.knows(de, "zurück") && VoskVocabulary.knows(de, "punk"))
        assertTrue(VoskVocabulary.knows(en, "points") && VoskVocabulary.knows(en, "undo"))
        for ((model, word) in listOf(de to "xqzvt", en to "xqzvt", de to "blaux", en to "bluex")) {
            assertTrue("model knows $word", !VoskVocabulary.knows(model, word))
        }
    }

    private fun run(language: Language, pcm: ShortArray, sensitivity: VoiceSensitivity): Run =
        run(CommandWords.defaults(language), pcm, sensitivity)

    private fun run(words: CommandWords, pcm: ShortArray, sensitivity: VoiceSensitivity): Run {
        val accepted = mutableListOf<ScoreCommand>()
        val heard = mutableListOf<Heard>()
        val at = mutableListOf<Int>()
        CommandRecognizer(models.getValue(words.language), words, sensitivity.minConfidence).use { rec ->
            val chunk = RATE / 10
            var i = 0
            fun take(h: Heard?, pos: Int) {
                h ?: return
                // The report shows each word's confidence: "point:0.84 blue:1.00".
                val words = rec.lastJson?.let { VoskResult.parse(it) }?.words.orEmpty()
                heard += if (words.isEmpty()) h else h.copy(text = words.joinToString(" ") { "${it.word}:${"%.2f".format(Locale.ROOT, it.confidence)}" })
                (h.verdict as? Verdict.Accepted)?.let { accepted += it.command; at += pos }
            }
            while (i < pcm.size) {
                val n = minOf(chunk, pcm.size - i)
                take(rec.accept(pcm.copyOfRange(i, i + n), n), i + n)
                i += n
            }
            take(rec.finish(), pcm.size)
        }
        return Run(accepted, heard, at)
    }

    /** The command clip in its surroundings; returns the stream and where the command ends. */
    private fun place(clip: Clip, condition: String): Pair<ShortArray, Int> {
        val seed = (clip.name + condition).hashCode().toLong()
        val neutral = neutral.getValue(clip.language)
        val random = Random(seed)
        val before = neutral[random.nextInt(neutral.size)]
        val after = neutral[random.nextInt(neutral.size)]
        val lead = ShortArray(RATE / 2)
        return when (condition) {
            "speech_around" -> (lead + before + clip.pcm + after + ShortArray(RATE * 3 / 2)) to (lead.size + before.size + clip.pcm.size)
            "noise_around" -> {
                val b1 = burst(RATE * 7 / 10, seed)
                (lead + b1 + clip.pcm + burst(RATE * 7 / 10, seed + 1) + ShortArray(RATE * 3 / 2)) to (lead.size + b1.size + clip.pcm.size)
            }
            "noise_bed" -> withBed(lead + clip.pcm + ShortArray(RATE * 5 / 2), clip.language, seed) to (lead.size + clip.pcm.size)
            "speech+bed" -> withBed(lead + before + clip.pcm + after + ShortArray(RATE * 2), clip.language, seed) to (lead.size + before.size + clip.pcm.size)
            else -> error(condition)
        }
    }

    companion object {
        private const val TAG = "VoiceSpottingTest"
        private const val RATE = CommandRecognizer.SAMPLE_RATE
        private val CONDITIONS = listOf("speech_around", "noise_around", "noise_bed", "speech+bed")
        /** 3.1.0 recognised 58 of 184 (32 %); 3.2.0 measured 142 of 184 (77 %). */
        private const val MIN_HIT_RATE = 0.70

        /**
         * The chatter is deliberately full of command words, and sentences meet without a gap
         * ("… das Team in Rot. Punkte …"). 3.1.0 gave a command in 20 of 144 streams at NORMAL, 3.2.0 4;
         * more than this is a regression.
         */
        private const val MAX_CHATTER_TRIGGERS = 5

        /** Target speech level after normalising (about -21 dBFS). */
        private const val SPEECH_RMS = 3000.0

        /** A sensible German replacement set: "Treffer Himmel" / "Feuer Treffer" ... */
        private val CUSTOM = CommandWords.of(
            Language.DE,
            CustomWords(blue = listOf("himmel"), red = listOf("feuer"), point = listOf("treffer")),
        )
        private val CUSTOM_COMMANDS = listOf(
            "point_blue" to "Treffer Himmel", "point_red" to "Treffer Feuer",
            "point_blue" to "Himmel Treffer", "point_red" to "Feuer Treffer",
        )

        private val models = mutableMapOf<Language, Model>()
        private val commands = mutableListOf<Clip>()
        private val customCommands = mutableListOf<Clip>()
        private val variantCommands = mutableListOf<Clip>()
        private val VARIANT_COMMANDS = listOf(
            Language.DE to ("point_blue" to "Punk Blau"), Language.DE to ("point_red" to "Rot Punk"),
            Language.EN to ("point_blue" to "points blue"), Language.EN to ("point_red" to "red points"),
        )
        private val chatterStreams = mutableListOf<Clip>()
        private val neutral = mutableMapOf<Language, List<ShortArray>>()
        private val babble = mutableMapOf<Language, ShortArray>()
        private val report = mutableListOf<String>()

        private fun line(s: String) {
            Log.i(TAG, s)
            report += s
        }

        private val VARIANTS = listOf(1.0f to 1.0f, 0.85f to 0.8f, 1.2f to 1.15f) // speech rate to pitch

        private val DE_COMMANDS = listOf(
            "point_blue" to "Punkt Blau", "point_red" to "Punkt Rot", "undo" to "zurück",
            "point_blue" to "Blau Punkt", "point_red" to "Rot Punkt",
        )
        private val EN_COMMANDS = listOf(
            "point_blue" to "point blue", "point_red" to "point red", "undo" to "undo",
            "point_blue" to "blue point", "point_red" to "red point",
        )
        private val DE_NEUTRAL = listOf("Der Ball war aus", "Schöner Aufschlag", "Weiter so, gut gemacht", "Fünf, drei", "Achtung, der Wind")
        private val EN_NEUTRAL = listOf("The ball was out", "Nice serve", "Keep going, well done", "Five, three", "Watch the wind")

        /** Chatter with the command words separately and not adjacent, and our own announcements. */
        private val DE_CHATTER = listOf(
            "Der Punkt ging knapp ins Aus, und dann hat Rot sich beschwert",
            "Blau spielt heute stark, aber jeder Punkt ist umkämpft",
            "Rot schlägt auf, und die Annahme kommt von Blau",
            "Das war ein schöner Punkt, wirklich schön gespielt",
            "Die blaue Mannschaft führt mit drei Punkten",
            "Satz Blau, fünfzehn, zwölf",
            "Blau führt, zwölf zu zehn",
            "Ein Punkt für die anderen, nicht für uns, das Team in Rot",
            "Wer hat den roten Ball gesehen, er lag eben noch hier",
            "Punkte zählen, Punkte zählen, das ist alles was zählt",
        )
        private val EN_CHATTER = listOf(
            "The point was close, and then red complained about it",
            "Blue is playing well today, but every point is a fight",
            "Red serves, and blue receives it nicely",
            "That was a great point, really well played",
            "The blue team leads by three points",
            "Set Red, twenty-one, nineteen",
            "Blue leads, twelve ten",
            "One point for them, not for us, the team in red",
            "Who has seen the red ball, it was here a moment ago",
            "Points matter, points matter, that is all that counts",
        )
        /** The undo word inside ordinary speech: the risky case for single-word commands. */
        private val DE_UNDO_CHATTER = listOf("Ich gehe kurz zurück zum Auto und hole Wasser", "Der Ball kam zurück über das Netz")
        private val EN_UNDO_CHATTER = listOf("Can you undo the knot in the net for me", "I need to undo my shoelaces first")

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val testContext = InstrumentationRegistry.getInstrumentation().context
            for (language in Language.entries) models[language] = Model(ModelStore(target).ensure(language).absolutePath)

            val texts = mutableListOf<Triple<Language, String, String>>() // language, key, text
            DE_COMMANDS.forEach { texts += Triple(Language.DE, "cmd:" + it.first, it.second) }
            CUSTOM_COMMANDS.forEach { texts += Triple(Language.DE, "custom:" + it.first, it.second) }
            VARIANT_COMMANDS.forEach { (language, c) -> texts += Triple(language, "variant:" + c.first, c.second) }
            EN_COMMANDS.forEach { texts += Triple(Language.EN, "cmd:" + it.first, it.second) }
            DE_NEUTRAL.forEach { texts += Triple(Language.DE, "neutral", it) }
            EN_NEUTRAL.forEach { texts += Triple(Language.EN, "neutral", it) }
            (DE_CHATTER + DE_UNDO_CHATTER).forEach { texts += Triple(Language.DE, "chatter", it) }
            (EN_CHATTER + EN_UNDO_CHATTER).forEach { texts += Triple(Language.EN, "chatter", it) }

            val neutralClips = mutableMapOf<Language, MutableList<ShortArray>>()
            val chatterClips = mutableMapOf<Language, MutableList<Pair<String, ShortArray>>>()
            for ((v, variant) in VARIANTS.withIndex()) {
                for ((key, pcm, text) in synthesize(target, texts, variant)) {
                    val (language, kind) = key
                    val clip = normalise(trim(pcm))
                    val slug = text.lowercase().replace(Regex("[^a-zäöüß]+"), "_").trim('_')
                    when {
                        kind.startsWith("variant:") ->
                            variantCommands += Clip("${language.tag}__tts${v}__$slug", language, command(kind.removePrefix("variant:")), clip)
                        kind.startsWith("custom:") ->
                            customCommands += Clip("${language.tag}__tts${v}__$slug", language, command(kind.removePrefix("custom:")), clip)
                        kind.startsWith("cmd:") -> commands += Clip("${language.tag}__tts${v}__$slug", language, command(kind.removePrefix("cmd:")), clip)
                        kind == "neutral" -> neutralClips.getOrPut(language) { mutableListOf() } += clip
                        else -> chatterClips.getOrPut(language) { mutableListOf() } += "${language.tag}__tts${v}__$slug" to clip
                    }
                }
            }
            // English SAPI voices: their commands, with their own neutral sentences around them.
            val assets = testContext.assets
            for (name in assets.list("voice").orEmpty().sorted()) {
                val (lang, expected, slug) = name.split("__")
                val language = Language.fromTag(lang)!!
                val pcm = normalise(trim(assets.open("voice/$name").use { VoiceRecognitionTest.readWav(it.readBytes()) }))
                when {
                    expected != "none" || slug == "blue_point" ->
                        commands += Clip(name.removeSuffix(".wav"), language, command(if (slug == "blue_point") "point_blue" else expected), pcm)
                    slug in setOf("ball_out", "nice_serve", "score", "correction") -> neutralClips.getOrPut(language) { mutableListOf() } += pcm
                }
            }
            neutralClips.forEach { (language, clips) -> neutral[language] = clips }

            // Chatter streams: each sentence of each voice variant, followed directly by two more.
            for ((language, clips) in chatterClips) {
                val random = Random(language.ordinal.toLong())
                for ((name, clip) in clips) {
                    val more = List(2) { clips[random.nextInt(clips.size)].second }
                    chatterStreams += Clip(name, language, null, ShortArray(RATE / 2) + clip + more[0] + more[1])
                }
                // Babble: all chatter of the language, three voices on top of each other.
                val all = clips.map { it.second }.reduce { a, b -> a + b }
                babble[language] = ShortArray(all.size) { i ->
                    ((all[i] + all[(i + all.size / 3) % all.size] + all[(i + 2 * all.size / 3) % all.size]) / 3)
                        .coerceIn(-32768, 32767).toShort()
                }
            }
            Log.i(TAG, "${commands.size} command clips, ${chatterStreams.size} chatter streams")
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            models.values.forEach { it.close() }
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            File(target.getExternalFilesDir(null), "voice-spotting-report.tsv").writeText(report.joinToString("\n"))
        }

        private fun command(expected: String): ScoreCommand? = when (expected) {
            "point_blue" -> ScoreCommand.TeamPoint(Team.BLUE)
            "point_red" -> ScoreCommand.TeamPoint(Team.RED)
            "undo" -> ScoreCommand.Undo
            else -> null
        }

        /** Continuous white noise (about -38 dBFS) plus background chatter about 12 dB under the speech. */
        private fun withBed(pcm: ShortArray, language: Language, seed: Long): ShortArray {
            val random = Random(seed)
            val talk = babble.getValue(language)
            val offset = random.nextInt(talk.size)
            val scale = SPEECH_RMS / 4 / rms(talk)
            return ShortArray(pcm.size) { i ->
                (pcm[i] + talk[(i + offset) % talk.size] * scale + random.nextGaussian() * 400).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        /** A loud noise burst (wind gust, crowd, ball on hands) at speech level. */
        private fun burst(length: Int, seed: Long): ShortArray {
            val random = Random(seed)
            var low = 0.0
            return ShortArray(length) { i ->
                low = 0.9 * low + 0.1 * random.nextGaussian() // reddish noise
                val envelope = minOf(1.0, i / 800.0, (length - i) / 800.0)
                (low * SPEECH_RMS * 4 * envelope).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        /** Cuts leading and trailing silence (10 ms frames below about -40 dBFS), keeping 20 ms. */
        private fun trim(pcm: ShortArray): ShortArray {
            val frame = RATE / 100
            val loud = (0 until pcm.size / frame).filter { f -> rms(pcm.copyOfRange(f * frame, (f + 1) * frame)) > 300 }
            if (loud.isEmpty()) return pcm
            val start = maxOf(0, loud.first() * frame - 2 * frame)
            val end = minOf(pcm.size, (loud.last() + 1) * frame + 2 * frame)
            return pcm.copyOfRange(start, end)
        }

        private fun normalise(pcm: ShortArray): ShortArray {
            val gain = SPEECH_RMS / rms(pcm)
            return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
        }

        private fun rms(pcm: ShortArray) = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size.coerceAtLeast(1))

        private fun stats(ms: List<Int>): String {
            if (ms.isEmpty()) return "-"
            val s = ms.sorted()
            return "median ${s[s.size / 2]} ms, 90% ${s[(s.size * 9 / 10).coerceAtMost(s.size - 1)]} ms, max ${s.last()} ms"
        }

        private fun describe(heard: List<Heard>) =
            heard.joinToString(" | ") { "\"${it.text}\" min=${"%.2f".format(Locale.ROOT, it.minConfidence ?: -1.0)} ${it.verdict}" }

        private fun synthesize(
            context: Context,
            texts: List<Triple<Language, String, String>>,
            variant: Pair<Float, Float>,
        ): List<Triple<Pair<Language, String>, ShortArray, String>> {
            val ready = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            val tts = TextToSpeech(context) { status = it; ready.countDown() }
            check(ready.await(20, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS) { "TTS init failed" }
            tts.setSpeechRate(variant.first)
            tts.setPitch(variant.second)
            val dir = File(context.cacheDir, "tts-spot").apply { mkdirs() }
            val out = mutableListOf<Triple<Pair<Language, String>, ShortArray, String>>()
            try {
                for ((i, t) in texts.withIndex()) {
                    val (language, key, text) = t
                    if (tts.setLanguage(Locale.forLanguageTag(language.tag)) < TextToSpeech.LANG_AVAILABLE) continue
                    val file = File(dir, "$i.wav")
                    val done = CountDownLatch(1)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit
                        override fun onDone(utteranceId: String) = done.countDown()
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String) = done.countDown()
                    })
                    tts.synthesizeToFile(text, null, file, "s$i")
                    check(done.await(20, TimeUnit.SECONDS)) { "TTS timed out for $text" }
                    out += Triple(language to key, VoiceRecognitionTest.readWav(file.readBytes()), text)
                }
            } finally {
                tts.shutdown()
            }
            return out
        }
    }
}
