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

    /**
     * The synthetic voices differ a little from run to run, so 3.3.0 is compared with the
     * 3.2.0 grammar on the same audio in one run. 3.2.0 = one point word, "points" a decoy
     * (English); the German grammar was the same then.
     */
    @Test
    fun sameAudioWithThe320Grammar() {
        fun baseline(language: Language) = CommandWords.defaults(language).let { w ->
            w.copy(point = w.point - w.alike, alike = emptySet(), replaced = if (language == Language.EN) listOf("points") else emptyList())
        }
        assertTrue(CommandParser.grammar(baseline(Language.EN)).contains("points"))
        val summary = mutableListOf<String>()
        val results = mutableListOf<Triple<Int, Int, Int>>() // hits, NORMAL triggers, RELAXED triggers
        for ((label, words) in listOf<Pair<String, (Language) -> CommandWords>>("3.2.0" to ::baseline, "3.3.0" to CommandWords::defaults)) {
            var hits = 0
            for (condition in CONDITIONS) for (clip in commands) {
                val run = run(words(clip.language), place(clip, condition).first, VoiceSensitivity.NORMAL, maxPauseMs = null)
                if (run.accepted == listOf(clip.expected)) hits++ else line("paired-$label\tmiss\t$condition\t${clip.name}\t${describe(run.heard)}")
            }
            val chatter = mutableListOf<String>()
            val triggers = mutableListOf<Int>()
            for (sensitivity in listOf(VoiceSensitivity.NORMAL, VoiceSensitivity.RELAXED)) {
                var count = 0
                for (stream in chatterStreams) for (bed in listOf(false, true)) {
                    val pcm = if (bed) withBed(stream.pcm, stream.language, stream.name.hashCode().toLong()) else stream.pcm
                    val run = run(words(stream.language), pcm + ShortArray(RATE), sensitivity, maxPauseMs = null)
                    if (run.accepted.isNotEmpty()) {
                        count++
                        line("paired-$label	$sensitivity	${stream.name}	$bed	TRIGGER ${run.accepted}	${describe(run.heard)}")
                    }
                }
                chatter += "$count at $sensitivity"
                triggers += count
            }
            summary += "same audio, $label grammar: ${hits} of ${commands.size * CONDITIONS.size} commands; chatter ${chatter.joinToString(", ")} (of ${chatterStreams.size * 2})"
            results += Triple(hits, triggers[0], triggers[1])
        }
        summary.forEach { line("# $it") }
        val (oldHits, oldNormal, oldRelaxed) = results[0]
        val (newHits, newNormal, newRelaxed) = results[1]
        val message = summary.joinToString("\n")
        assertTrue(message, newHits >= oldHits - MAX_FEWER_COMMANDS_VS_320)
        assertTrue(message, newNormal <= oldNormal + MAX_EXTRA_TRIGGERS_VS_320)
        assertTrue(message, newRelaxed <= oldRelaxed + MAX_EXTRA_TRIGGERS_VS_320)
    }

    /**
     * 3.3.1: "Punkt … Blau" with a pause. Each half synthesised on its own, placed with about
     * 0.7 s, 1.5 s and 2.5 s of silence between them (both orders, both languages, three
     * voices), in quiet and under the continuous noise bed; recognised at each setting.
     * At 2 s a 2.5 s pause must not score; at 3 s it should.
     */
    @Test
    fun commandsWithAPauseBetweenTheWords() {
        val summary = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        // Under the bed, background chatter full of command words can fill the pause
        // ("point" + a background "red"): reported, not a gate.
        val bedWrong = mutableListOf<String>()
        val hits = mutableMapOf<Pair<Long, Int>, Int>() // (setting, pause) to hits
        val totals = mutableMapOf<Int, Int>()
        val latencies = mutableMapOf<Long, MutableList<Int>>()
        for (setting in HalfJoiner.PAUSE_CHOICES_MS) for (clip in pausedCommands) for (bed in listOf(false, true)) {
            val pcm = if (bed) withBed(clip.pcm, clip.language, clip.name.hashCode().toLong()) else clip.pcm
            val run = run(CommandWords.defaults(clip.language), pcm, VoiceSensitivity.NORMAL, setting)
            val hit = run.accepted == listOf(clip.command.expected)
            if (setting == HalfJoiner.DEFAULT_PAUSE_MS) totals.merge(clip.pauseMs, 1, Int::plus)
            if (hit) {
                hits.merge(setting to clip.pauseMs, 1, Int::plus)
                latencies.getOrPut(setting) { mutableListOf() } += (run.acceptedAt.first() - clip.end) * 1000 / RATE
            }
            if (run.accepted.any { it != clip.command.expected }) (if (bed) bedWrong else wrong) += "$setting ${clip.command.name} bed=$bed: ${describe(run.heard)}"
            line("paused-$setting\t${clip.command.name}\t${if (bed) "bed" else "quiet"}\t${if (hit) "HIT" else "miss"}\t${describe(run.heard)}")
        }
        for (setting in HalfJoiner.PAUSE_CHOICES_MS) {
            val perPause = PAUSES_MS.joinToString(", ") { "${it / 1000.0} s: ${hits[setting to it] ?: 0} of ${totals[it]}" }
            summary += "pause setting ${setting / 1000} s: $perPause; second word end -> accepted ${stats(latencies[setting].orEmpty())}"
        }
        summary += "paused commands, wrong: ${wrong.size} in quiet, ${bedWrong.size} under the noise bed"
        summary.forEach { line("# $it") }
        (wrong + bedWrong).forEach { line("WRONG paused $it") }
        val message = summary.joinToString("\n") + "\n" + wrong.joinToString("\n")
        assertTrue(message, wrong.isEmpty())
        assertTrue(message, (hits[HalfJoiner.DEFAULT_PAUSE_MS to 2500] ?: 0) == 0)
        assertTrue(message, (hits[3000L to 2500] ?: 0) >= totals.getValue(2500) / 2)
        assertTrue(message, (hits[HalfJoiner.DEFAULT_PAUSE_MS to 1500] ?: 0) >= totals.getValue(1500) / 2)
    }

    /**
     * 3.3.1 against the 3.3.0 recogniser (no single words in the grammar, no joining) on the
     * same audio: commands said in one go, and chatter at each pause setting.
     */
    @Test
    fun sameAudioWithThe330Recogniser() {
        val summary = mutableListOf<String>()
        val results = mutableMapOf<Long?, Triple<Int, Int, Int>>()
        for (setting in listOf<Long?>(null) + HalfJoiner.PAUSE_CHOICES_MS) {
            val label = setting?.let { "3.3.1 ${it / 1000} s" } ?: "3.3.0"
            var hits = 0
            var wrong = 0
            val latencies = mutableListOf<Int>()
            for (condition in CONDITIONS) for (clip in commands) {
                val (pcm, end) = place(clip, condition)
                val run = run(CommandWords.defaults(clip.language), pcm, VoiceSensitivity.NORMAL, setting)
                if (run.accepted == listOf(clip.expected)) {
                    hits++
                    latencies += (run.acceptedAt.first() - end) * 1000 / RATE
                } else {
                    line("paired330-$label\tmiss\t$condition\t${clip.name}\t${describe(run.heard)}")
                }
                if (run.accepted.any { it != clip.expected }) wrong++
            }
            val counts = mutableListOf<Int>()
            for (sensitivity in listOf(VoiceSensitivity.NORMAL, VoiceSensitivity.RELAXED)) {
                var count = 0
                for (stream in chatterStreams) for (bed in listOf(false, true)) {
                    val pcm = if (bed) withBed(stream.pcm, stream.language, stream.name.hashCode().toLong()) else stream.pcm
                    val run = run(CommandWords.defaults(stream.language), pcm + ShortArray(RATE), sensitivity, setting)
                    if (run.accepted.isNotEmpty()) {
                        count++
                        line("paired330-$label\t$sensitivity\t${stream.name}\t$bed\tTRIGGER ${run.accepted}\t${describe(run.heard)}")
                    }
                }
                counts += count
            }
            summary += "same audio, $label: $hits of ${commands.size * CONDITIONS.size} commands in one go (latency ${stats(latencies)}), " +
                "wrong $wrong; chatter ${counts[0]} at NORMAL, ${counts[1]} at RELAXED (of ${chatterStreams.size * 2})"
            results[setting] = Triple(hits, counts[0], counts[1])
            assertTrue("wrong commands with $label", wrong == 0)
        }
        summary.forEach { line("# $it") }
        val (oldHits, oldNormal, oldRelaxed) = results.getValue(null)
        val (newHits, newNormal, newRelaxed) = results.getValue(HalfJoiner.DEFAULT_PAUSE_MS)
        val message = summary.joinToString("\n")
        assertTrue(message, newHits >= oldHits - MAX_FEWER_COMMANDS_VS_330)
        assertTrue(message, newNormal <= oldNormal + MAX_EXTRA_TRIGGERS_VS_330 && newRelaxed <= oldRelaxed + MAX_EXTRA_TRIGGERS_VS_330)
    }

    /**
     * Option (brief item 6): Vosk's endpointer waits the pause setting instead of 0.5 s, so
     * "punkt … blau" can stay one utterance and be heard as the phrase. Measured against the
     * production recogniser on the same audio.
     */
    @Test
    fun pauseWithALongerEndpointer() {
        val summary = mutableListOf<String>()
        for (long in listOf(false, true)) {
            val label = if (long) "long endpointer" else "default endpointer"
            for (setting in listOf(2000L, 3000L)) {
                val hits = mutableMapOf<Int, Int>()
                var wrong = 0
                for (clip in pausedCommands) for (bed in listOf(false, true)) {
                    val pcm = if (bed) withBed(clip.pcm, clip.language, clip.name.hashCode().toLong()) else clip.pcm
                    val run = run(CommandWords.defaults(clip.language), pcm, VoiceSensitivity.NORMAL, setting, long)
                    val hit = run.accepted == listOf(clip.command.expected)
                    if (hit) hits.merge(clip.pauseMs, 1, Int::plus)
                    if (run.accepted.any { it != clip.command.expected }) wrong++
                    line("endpointer-$long-$setting	${clip.command.name}	${if (bed) "bed" else "quiet"}	${if (hit) "HIT" else "miss"}	${describe(run.heard)}")
                }
                summary += "$label, pause setting ${setting / 1000} s: " + PAUSES_MS.joinToString(", ") { "${it / 1000.0} s: ${hits[it] ?: 0} of ${pausedCommands.count { c -> c.pauseMs == it } * 2}" } + ", wrong $wrong"
            }
            var oneGo = 0
            for (condition in CONDITIONS) for (clip in commands) {
                val run = run(CommandWords.defaults(clip.language), place(clip, condition).first, VoiceSensitivity.NORMAL, 2000L, long)
                if (run.accepted == listOf(clip.expected)) oneGo++ else line("endpointer-$long-onego	miss	$condition	${clip.name}	${describe(run.heard)}")
            }
            val counts = mutableListOf<Int>()
            for (sensitivity in listOf(VoiceSensitivity.NORMAL, VoiceSensitivity.RELAXED)) {
                var count = 0
                for (stream in chatterStreams) for (bed in listOf(false, true)) {
                    val pcm = if (bed) withBed(stream.pcm, stream.language, stream.name.hashCode().toLong()) else stream.pcm
                    val run = run(CommandWords.defaults(stream.language), pcm + ShortArray(RATE * 3), sensitivity, 2000L, long)
                    if (run.accepted.isNotEmpty()) {
                        count++
                        line("endpointer-$long-chatter	$sensitivity	${stream.name}	$bed	TRIGGER ${run.accepted}	${describe(run.heard)}")
                    }
                }
                counts += count
            }
            summary += "$label at 2 s: one go $oneGo of ${commands.size * CONDITIONS.size}; chatter ${counts[0]} NORMAL, ${counts[1]} RELAXED"
        }
        summary.forEach { line("# $it") }
    }

    /**
     * 3.5.0: one-word mode (3.4.1, point field empty): a team word alone scores. Which pair of
     * words is reliable said alone? Each word of each pair (three TTS voices) in quiet, between
     * noise bursts, between neutral sentences and under the noise bed; and the chatter streams.
     *
     * Every run is done once at the lowest threshold (0.30) and tabulated for higher ones: the
     * threshold only decides LOW_CONFIDENCE, so a command accepted at 0.30 with confidence c is
     * accepted at T exactly when c >= T. Measurement only: nothing is asserted.
     *
     * Bias: the chatter (and the babble of the noise bed) is full of "Blau"/"Rot" by design and
     * contains none of the other candidates, so blau/rot get every chatter sentence as a trigger
     * and the others only mis-hearings.
     */
    @Test
    fun oneWordTeamWords() {
        val floor = VoiceThreshold.toConfidence(VoiceThreshold.MIN_PCT)
        val thresholds = listOf(0.85, 0.70, 0.60, 0.50, 0.40)
        val summary = mutableListOf<String>()
        for ((language, pair) in ONE_WORD_PAIRS) {
            val (blue, red) = pair
            val model = models.getValue(language)
            val missing = listOf(blue, red).filterNot { VoskVocabulary.knows(model, it) }
            if (missing.isNotEmpty()) {
                summary += "${language.tag} $blue/$red: not in the model: $missing (skipped)"
                continue
            }
            val words = CommandWords.of(language, CustomWords(blue = listOf(blue), red = listOf(red), pointOff = true))
            val clips = oneWordClips.filter { it.language == language && it.name.substringAfterLast("__") in setOf(blue, red) }
            // Per threshold: [quiet hits, quiet n, noisy hits, noisy n, wrong].
            val counts = thresholds.associateWith { IntArray(5) }
            val confidences = mutableListOf<Double>()
            for (clip in clips) for (condition in listOf("quiet") + CONDITIONS) {
                val pcm = if (condition == "quiet") ShortArray(RATE / 2) + clip.pcm + ShortArray(RATE * 5 / 2) else place(clip, condition).first
                val run = run(words, pcm, VoiceSensitivity.NORMAL, threshold = floor)
                val scored = run.heard.mapNotNull { h -> (h.verdict as? Verdict.Accepted)?.let { it.command to (h.minConfidence ?: 0.0) } }
                scored.filter { it.first == clip.expected }.maxOfOrNull { it.second }?.let { confidences += it }
                val quiet = condition == "quiet" || condition == "speech_around"
                for (t in thresholds) {
                    val at = scored.filter { it.second >= t }.map { it.first }
                    val c = counts.getValue(t)
                    val hit = at.isNotEmpty() && at.all { it == clip.expected }
                    if (quiet) { c[1]++; if (hit) c[0]++ } else { c[3]++; if (hit) c[2]++ }
                    if (at.any { it != clip.expected }) c[4]++
                }
                line("oneword-${language.tag}-$blue/$red\t$condition\t${clip.name}\t${describe(run.heard)}")
            }
            val chatter = thresholds.associateWith { 0 }.toMutableMap()
            var streams = 0
            for (stream in chatterStreams.filter { it.language == language }) for (bed in listOf(false, true)) {
                val pcm = if (bed) withBed(stream.pcm, stream.language, stream.name.hashCode().toLong()) else stream.pcm
                val run = run(words, pcm + ShortArray(RATE), VoiceSensitivity.NORMAL, threshold = floor)
                streams++
                val conf = run.heard.filter { it.verdict is Verdict.Accepted }.maxOfOrNull { it.minConfidence ?: 0.0 }
                for (t in thresholds) if (conf != null && conf >= t) chatter[t] = chatter.getValue(t) + 1
                if (conf != null) line("oneword-chatter-${language.tag}-$blue/$red\t${stream.name}\t$bed\t${describe(run.heard)}")
            }
            val sorted = confidences.sorted()
            val spread = if (sorted.isEmpty()) "-" else
                "min %.2f, 10%% %.2f, median %.2f".format(Locale.ROOT, sorted.first(), sorted[sorted.size / 10], sorted[sorted.size / 2])
            for (t in thresholds) {
                val c = counts.getValue(t)
                summary += "${language.tag} $blue/$red at %.2f: quiet ${c[0]} of ${c[1]}, noisy ${c[2]} of ${c[3]}, wrong ${c[4]}, chatter ${chatter[t]} of $streams".format(Locale.ROOT, t)
            }
            summary += "${language.tag} $blue/$red: confidence of the right word when heard: $spread (${sorted.size} of ${clips.size * (CONDITIONS.size + 1)} runs)"
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

    private fun run(
        words: CommandWords,
        pcm: ShortArray,
        sensitivity: VoiceSensitivity,
        maxPauseMs: Long? = HalfJoiner.DEFAULT_PAUSE_MS,
        longEndpointer: Boolean = true,
        threshold: Double = sensitivity.minConfidence,
    ): Run {
        val accepted = mutableListOf<ScoreCommand>()
        val heard = mutableListOf<Heard>()
        val at = mutableListOf<Int>()
        CommandRecognizer(models.getValue(words.language), words, threshold, maxPauseMs = maxPauseMs, longEndpointer = longEndpointer).use { rec ->
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
         * ("… das Team in Rot. Punkte …"). 3.1.0 gave a command in 20 of 144 streams at NORMAL, 3.2.0 4.
         * The synthetic voices vary between emulator boots: over five 3.3.0 runs 4-6 at NORMAL
         * and RELAXED, and the 3.2.0 grammar on the same audio up to 6 as well. More than this is
         * a regression; [sameAudioWithThe320Grammar] is the precise comparison.
         */
        private const val MAX_CHATTER_TRIGGERS = 6

        /**
         * "points" is a point word since 3.3.0, so chatter such as "… three points. Red serves …"
         * can now give a point: one more trigger than the 3.2.0 grammar on the same audio is
         * accepted (four paired runs: NORMAL 5 against 4 three times and 4 against 4 once;
         * RELAXED 4 against 6 three times and 4 against 4 once).
         */
        private const val MAX_EXTRA_TRIGGERS_VS_320 = 1

        /**
         * The same comparison for recognised commands. Four paired runs: 142 against 142 three
         * times, 141 against 142 once ("points" is no longer a decoy, the rest is identical).
         */
        private const val MAX_FEWER_COMMANDS_VS_320 = 1

        /**
         * 3.3.1 against 3.3.0 on the same audio: the single command words in the grammar take a
         * little confidence from a few weak pairs (first run: 140 against 142, e.g. "punkt:0.65
         * blau" after "schöner Aufschlag"); chatter at the default 2 s with the longer
         * endpointer was 4 against 4.
         */
        private const val MAX_FEWER_COMMANDS_VS_330 = 2
        private const val MAX_EXTRA_TRIGGERS_VS_330 = 1

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

        /** 3.5.0: candidate team words for one-word mode (blue, red); the first pair is the default. */
        private val ONE_WORD_PAIRS = listOf(
            Language.DE to ("blau" to "rot"), Language.DE to ("himmel" to "feuer"),
            Language.DE to ("ozean" to "vulkan"), Language.DE to ("delfin" to "tiger"),
            Language.EN to ("blue" to "red"), Language.EN to ("ocean" to "volcano"),
        )
        private val oneWordClips = mutableListOf<Clip>()

        private val models = mutableMapOf<Language, Model>()
        private val commands = mutableListOf<Clip>()
        private val customCommands = mutableListOf<Clip>()
        private val variantCommands = mutableListOf<Clip>()
        private val VARIANT_COMMANDS = listOf(
            Language.DE to ("point_blue" to "Punk Blau"), Language.DE to ("point_red" to "Rot Punk"),
            Language.EN to ("point_blue" to "points blue"), Language.EN to ("point_red" to "red points"),
        )
        private val chatterStreams = mutableListOf<Clip>()

        /** 3.3.1: the halves on their own, per language and voice: "point", "blue", "red". */
        private val DE_HALVES = listOf("point" to "Punkt", "blue" to "Blau", "red" to "Rot")
        private val EN_HALVES = listOf("point" to "point", "blue" to "blue", "red" to "red")
        private val PAUSES_MS = listOf(700, 1500, 2500)

        /** A command said with [pauseMs] of silence between its words; [end]: end of the second word. */
        private class Paused(val command: Clip, val pauseMs: Int, val end: Int) {
            val pcm get() = command.pcm
            val name get() = command.name
            val language get() = command.language
        }
        private val pausedCommands = mutableListOf<Paused>()
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
            DE_HALVES.forEach { texts += Triple(Language.DE, "half:" + it.first, it.second) }
            EN_HALVES.forEach { texts += Triple(Language.EN, "half:" + it.first, it.second) }
            for ((language, pair) in ONE_WORD_PAIRS) {
                texts += Triple(language, "one:point_blue:" + pair.first, pair.first.replaceFirstChar { it.uppercase() })
                texts += Triple(language, "one:point_red:" + pair.second, pair.second.replaceFirstChar { it.uppercase() })
            }
            DE_NEUTRAL.forEach { texts += Triple(Language.DE, "neutral", it) }
            EN_NEUTRAL.forEach { texts += Triple(Language.EN, "neutral", it) }
            (DE_CHATTER + DE_UNDO_CHATTER).forEach { texts += Triple(Language.DE, "chatter", it) }
            (EN_CHATTER + EN_UNDO_CHATTER).forEach { texts += Triple(Language.EN, "chatter", it) }

            val neutralClips = mutableMapOf<Language, MutableList<ShortArray>>()
            val chatterClips = mutableMapOf<Language, MutableList<Pair<String, ShortArray>>>()
            val halves = mutableMapOf<Triple<Language, Int, String>, ShortArray>()
            for ((v, variant) in VARIANTS.withIndex()) {
                for ((key, pcm, text) in synthesize(target, texts, variant)) {
                    val (language, kind) = key
                    val clip = normalise(trim(pcm))
                    val slug = text.lowercase().replace(Regex("[^a-zäöüß]+"), "_").trim('_')
                    when {
                        kind.startsWith("half:") -> halves[Triple(language, v, kind.removePrefix("half:"))] = clip
                        kind.startsWith("one:") -> {
                            val (_, expected, word) = kind.split(":")
                            oneWordClips += Clip("${language.tag}__tts${v}__$word", language, command(expected), clip)
                        }
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

            // Paused commands: half, silence, other half; both orders.
            for ((language, v, kind) in halves.keys.filter { it.third != "point" }) {
                val point = halves[Triple(language, v, "point")] ?: continue
                val team = halves.getValue(Triple(language, v, kind))
                for (pointFirst in listOf(true, false)) for (pauseMs in PAUSES_MS) {
                    val (a, b) = if (pointFirst) point to team else team to point
                    val lead = ShortArray(RATE / 2)
                    val gap = ShortArray(RATE * pauseMs / 1000)
                    val pcm = lead + a + gap + b + ShortArray(RATE * 3 / 2)
                    val order = if (pointFirst) "point_$kind" else "${kind}_point"
                    val clip = Clip("${language.tag}__tts${v}__${order}__${pauseMs}ms", language, command("point_$kind"), pcm)
                    pausedCommands += Paused(clip, pauseMs, lead.size + a.size + gap.size + b.size)
                }
            }

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
            heard.joinToString(" | ") { "\"${it.text}\"" + (if ('‥' in it.display) " [${it.display}]" else "") + " min=${"%.2f".format(Locale.ROOT, it.minConfidence ?: -1.0)} ${it.verdict}" }

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
