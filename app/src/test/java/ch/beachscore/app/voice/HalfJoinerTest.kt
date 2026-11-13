package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HalfJoinerTest {

    /** A word with its confidence and its time span in seconds. */
    private data class W(val word: String, val start: Double, val end: Double = start + 0.4, val conf: Double = 1.0)

    private fun result(vararg words: W) = VoskResult(
        words.joinToString(" ") { it.word },
        words.map { VoskResult.Word(it.word, it.conf, it.start, it.end) },
    )

    private class Rig(val words: CommandWords, maxPauseMs: Long = 2000, val sensitivity: VoiceSensitivity = VoiceSensitivity.NORMAL) {
        val joiner = HalfJoiner(words, maxPauseMs, sensitivity.minConfidence)
        fun hear(r: VoskResult): Heard? = joiner.join(r, CommandParser.parse(r, words, sensitivity.minConfidence))
    }

    private fun rig(language: Language = Language.DE, maxPauseMs: Long = 2000) = Rig(CommandWords.defaults(language), maxPauseMs)

    private fun command(heard: Heard?) = (heard?.verdict as? Verdict.Accepted)?.command
    private fun reason(heard: Heard?) = (heard?.verdict as? Verdict.Rejected)?.reason

    private val blue = ScoreCommand.TeamPoint(Team.BLUE)
    private val red = ScoreCommand.TeamPoint(Team.RED)

    @Test
    fun `halves in two results join, both orders`() {
        val r = rig()
        assertEquals(RejectReason.INCOMPLETE, reason(r.hear(result(W("punkt", 0.0)))))
        assertEquals(blue, command(r.hear(result(W("blau", 1.4)))))

        assertEquals(RejectReason.INCOMPLETE, reason(r.hear(result(W("rot", 5.0)))))
        assertEquals(red, command(r.hear(result(W("punkt", 6.0)))))
    }

    @Test
    fun `halves in one result with only noise between join`() {
        val r = rig(Language.EN)
        assertEquals(blue, command(r.hear(result(W("point", 0.0), W("[unk]", 0.6), W("blue", 1.2)))))
        assertEquals(red, command(r.hear(result(W("[unk]", 3.0), W("red", 3.5), W("point", 5.0)))))
    }

    @Test
    fun `noise only results between the halves keep the half`() {
        val r = rig()
        r.hear(result(W("punkt", 0.0)))
        assertEquals(RejectReason.UNKNOWN, reason(r.hear(result(W("[unk]", 0.8)))))
        assertNull(r.hear(VoskResult("", emptyList())))
        assertEquals(blue, command(r.hear(result(W("[unk]", 1.2), W("blau", 1.8)))))
    }

    @Test
    fun `the window is the pause from the end of the first half to the start of the second`() {
        val r = rig(maxPauseMs = 2000)
        r.hear(result(W("punkt", 0.0, end = 0.4)))
        assertEquals(blue, command(r.hear(result(W("blau", 2.4)))))
        r.hear(result(W("punkt", 10.0, end = 10.4)))
        val late = r.hear(result(W("blau", 12.5)))
        assertEquals(RejectReason.PAUSE_TOO_LONG, reason(late))
        assertEquals("punkt ‥ blau (2.1 s)", late!!.display)

        val wide = rig(maxPauseMs = 3000)
        wide.hear(result(W("punkt", 10.0, end = 10.4)))
        assertEquals(blue, command(wide.hear(result(W("blau", 12.9)))))
    }

    @Test
    fun `adjacent command words with a long silence between them obey the window too`() {
        // With a longer endpointer both halves can arrive in one result, adjacent.
        val r = rig(maxPauseMs = 2000)
        val late = r.hear(result(W("punkt", 0.0, end = 0.4), W("blau", 2.9)))
        assertEquals(RejectReason.PAUSE_TOO_LONG, reason(late))
        assertEquals("punkt ‥ blau (2.5 s)", late!!.display)
        val inTime = r.hear(result(W("blau", 10.0, end = 10.4), W("punkt", 11.9)))
        assertEquals(blue, command(inTime))
        assertEquals("blau ‥ punkt (1.5 s)", inTime!!.display)
        // Said in one go: unchanged.
        val normal = r.hear(result(W("[unk]", 20.0), W("punkt", 20.5, end = 20.9), W("rot", 20.9)))
        assertEquals(red, command(normal))
        assertEquals("… punkt rot", normal!!.display)
    }

    @Test
    fun `a late second half is remembered in turn`() {
        val r = rig(maxPauseMs = 1000)
        r.hear(result(W("blau", 0.0)))
        assertEquals(RejectReason.PAUSE_TOO_LONG, reason(r.hear(result(W("punkt", 5.0)))))
        // "punkt" waits now; "rot" right after it joins with it.
        assertEquals(red, command(r.hear(result(W("rot", 5.8)))))
    }

    @Test
    fun `other words between the halves drop the half`() {
        val r = rig()
        r.hear(result(W("punkt", 0.0)))
        assertEquals(RejectReason.UNKNOWN, reason(r.hear(result(W("das", 0.6), W("war", 0.9)))))
        assertEquals(RejectReason.INCOMPLETE, reason(r.hear(result(W("blau", 1.5)))))

        val s = rig()
        assertNull(command(s.hear(result(W("punkt", 0.0), W("ist", 0.5), W("blau", 1.0)))))
    }

    @Test
    fun `two halves of the same kind - the last one waits`() {
        val r = rig()
        r.hear(result(W("blau", 0.0)))
        r.hear(result(W("rot", 1.0)))
        assertEquals(red, command(r.hear(result(W("punkt", 2.0)))))
    }

    @Test
    fun `a full command drops a remembered half`() {
        val r = rig()
        r.hear(result(W("punkt", 0.0)))
        assertEquals(red, command(r.hear(result(W("rot", 1.0), W("punkt", 1.4)))))
        // The "punkt" of the full command is not remembered either.
        assertEquals(RejectReason.INCOMPLETE, reason(r.hear(result(W("blau", 2.0)))))
    }

    @Test
    fun `reset drops the half`() {
        val r = rig()
        r.hear(result(W("punkt", 0.0)))
        r.joiner.reset()
        assertEquals(RejectReason.INCOMPLETE, reason(r.hear(result(W("blau", 1.0)))))
    }

    @Test
    fun `undo is never a half`() {
        val r = rig()
        r.hear(result(W("punkt", 0.0)))
        assertEquals(ScoreCommand.Undo, command(r.hear(result(W("zurück", 0.8)))))
        assertEquals(RejectReason.INCOMPLETE, reason(r.hear(result(W("blau", 1.5)))))
    }

    @Test
    fun `confidence of a join is the lower of the halves`() {
        val r = rig()
        r.hear(result(W("punkt", 0.0, conf = 0.80)))
        val heard = r.hear(result(W("blau", 1.0, conf = 1.0)))
        assertEquals(RejectReason.LOW_CONFIDENCE, reason(heard))
        assertEquals(0.80, heard!!.minConfidence!!, 1e-9)
        // A rejected join is used up.
        assertEquals(RejectReason.INCOMPLETE, reason(r.hear(result(W("punkt", 2.0)))))

        val relaxed = Rig(CommandWords.defaults(Language.DE), sensitivity = VoiceSensitivity.RELAXED)
        relaxed.hear(result(W("punkt", 0.0, conf = 0.80)))
        assertEquals(blue, command(relaxed.hear(result(W("blau", 1.0, conf = 0.95)))))
    }

    @Test
    fun `the first half obeys the in-speech rule`() {
        val r = rig()
        r.hear(result(W("das", 0.0), W("war", 0.3), W("ein", 0.6), W("schöner", 0.9), W("ball", 1.2), W("punkt", 1.5)))
        assertEquals(RejectReason.INCOMPLETE, reason(r.hear(result(W("blau", 2.2)))))
        val s = rig()
        s.hear(result(W("das", 0.0), W("war", 0.3), W("gut", 0.6), W("punkt", 1.0)))
        assertEquals(blue, command(s.hear(result(W("blau", 2.0)))))
    }

    @Test
    fun `custom words and alike variants`() {
        val custom = Rig(CommandWords.of(Language.DE, CustomWords(blue = listOf("himmel"), point = listOf("treffer"))))
        custom.hear(result(W("treffer", 0.0)))
        assertEquals(blue, command(custom.hear(result(W("himmel", 1.0)))))
        custom.hear(result(W("punkt", 3.0)))
        assertEquals(RejectReason.INCOMPLETE, reason(custom.hear(result(W("himmel", 4.0)))))

        val r = rig()
        r.hear(result(W("punk", 0.0)))
        assertEquals(blue, command(r.hear(result(W("blau", 1.0)))))
    }

    @Test
    fun `the display shows the half and the join`() {
        val r = rig(Language.EN)
        assertEquals("point ‥", r.hear(result(W("[unk]", 0.0), W("point", 0.5)))!!.display)
        assertEquals("point ‥ blue (1.6 s)", r.hear(result(W("blue", 2.5)))!!.display)
    }

    @Test
    fun `without word times nothing is joined`() {
        val r = rig()
        r.hear(result(W("punkt", 0.0)))
        val noTimes = VoskResult("blau", listOf(VoskResult.Word("blau", 1.0)))
        assertNull(command(r.hear(noTimes)))
    }

    @Test
    fun `completes tells the early finalizer where a join ends`() {
        val r = rig()
        assertNull(r.joiner.completes(listOf("[unk]", "punkt")))
        assertEquals(2, r.joiner.completes(listOf("punkt", "[unk]", "blau")))
        assertNull(r.joiner.completes(listOf("punkt", "das", "blau")))
        r.hear(result(W("punkt", 0.0)))
        assertEquals(1, r.joiner.completes(listOf("[unk]", "blau")))
        assertEquals(0, r.joiner.completes(listOf("rot", "[unk]")))
        assertNull(r.joiner.completes(listOf("punkt")))
        assertNull(r.joiner.completes(listOf("ja", "blau")))
        assertTrue(r.joiner.pending != null)
        r.joiner.reset()
        assertFalse(r.joiner.pending != null)
    }
}
