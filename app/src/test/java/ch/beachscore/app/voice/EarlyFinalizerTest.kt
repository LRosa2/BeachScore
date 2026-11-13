package ch.beachscore.app.voice

import ch.beachscore.app.score.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EarlyFinalizerTest {

    private val ms = CommandRecognizer.SAMPLE_RATE / 1000

    /** Feeds partial results every 100 ms; returns the time (ms) at which it asks to finalise, or null. */
    private fun firstFinalise(f: EarlyFinalizer, vararg partials: String): Int? {
        partials.forEachIndexed { i, p -> if (f.shouldFinalize(p, (i + 1) * 100L * ms)) return (i + 1) * 100 }
        return null
    }

    @Test
    fun `nothing happens without a command in the partial result`() {
        val f = EarlyFinalizer(Language.DE)
        assertEquals(null, firstFinalise(f, "", "[unk]", "[unk] punkt", "[unk] punkt", "[unk] punkt", "[unk] punkt [unk] blau", "[unk] punkt [unk] blau"))
    }

    @Test
    fun `a point command at the end is finalised once it has been stable briefly`() {
        val f = EarlyFinalizer(Language.DE)
        // Heard first at 300 ms, unchanged at 400 and 500 ms.
        assertEquals(300 + EarlyFinalizer.POINT_STABLE_MS, firstFinalise(f, "[unk]", "[unk] punkt", "[unk] punkt blau", "[unk] punkt blau", "[unk] punkt blau", "[unk] punkt blau"))
    }

    @Test
    fun `a point command followed by anything is finalised at once`() {
        val f = EarlyFinalizer(Language.EN)
        assertEquals(300, firstFinalise(f, "point", "point red", "point red [unk]"))
        f.reset()
        assertEquals(200, firstFinalise(f, "blue", "blue point red"))
    }

    @Test
    fun `undo waits longer, to see whether more speech follows`() {
        val f = EarlyFinalizer(Language.DE)
        assertEquals(100 + EarlyFinalizer.UNDO_STABLE_MS, firstFinalise(f, *Array(12) { "zurück" }))
        f.reset()
        assertEquals(300, firstFinalise(f, "zurück", "zurück", "zurück [unk]"))
        assertTrue(EarlyFinalizer.UNDO_STABLE_MS > EarlyFinalizer.POINT_STABLE_MS)
    }

    @Test
    fun `a changing partial restarts the wait`() {
        val f = EarlyFinalizer(Language.EN)
        assertFalse(f.shouldFinalize("point blue", 100L * ms))
        assertFalse(f.shouldFinalize("[unk] point blue", 200L * ms))
        assertFalse(f.shouldFinalize("[unk] point blue", (200L + EarlyFinalizer.POINT_STABLE_MS - 10) * ms))
        assertTrue(f.shouldFinalize("[unk] point blue", (200L + EarlyFinalizer.POINT_STABLE_MS) * ms))
    }

    @Test
    fun `after a reset it starts over`() {
        val f = EarlyFinalizer(Language.EN)
        f.shouldFinalize("undo", 100L * ms)
        f.reset()
        assertFalse(f.shouldFinalize("undo", 2000L * ms))
    }

    @Test
    fun `words of the other language are ignored`() {
        val f = EarlyFinalizer(Language.EN)
        assertEquals(null, firstFinalise(f, "punkt blau", "punkt blau", "punkt blau", "punkt blau", "punkt blau", "punkt blau"))
    }

    @Test
    fun `custom words are watched instead of the defaults`() {
        val words = CommandWords.of(Language.DE, CustomWords(point = listOf("tor")))
        val f = EarlyFinalizer(words)
        assertEquals(null, firstFinalise(f, "punkt blau", "punkt blau", "punkt blau", "punkt blau", "punkt blau"))
        f.reset()
        assertEquals(200, firstFinalise(f, "blau tor", "blau tor [unk]"))
    }

    private fun joined(language: Language = Language.DE): Pair<EarlyFinalizer, HalfJoiner> {
        val words = CommandWords.defaults(language)
        val joiner = HalfJoiner(words, 2000, VoiceSensitivity.NORMAL.minConfidence)
        return EarlyFinalizer(words, joiner = joiner) to joiner
    }

    private fun remember(joiner: HalfJoiner, word: String) {
        val r = VoskResult(word, listOf(VoskResult.Word(word, 1.0, 0.0, 0.4)))
        joiner.join(r, Heard(word, 1.0, Verdict.Rejected(RejectReason.UNKNOWN)))
    }

    @Test
    fun `with a remembered half the matching half is finalised like a command`() {
        val (f, joiner) = joined()
        remember(joiner, "punkt")
        // Stable for 200 ms after it first shows.
        assertEquals(200 + EarlyFinalizer.POINT_STABLE_MS, firstFinalise(f, "[unk]", "[unk] blau", "[unk] blau", "[unk] blau", "[unk] blau"))
        f.reset()
        // Followed by anything: at once.
        assertEquals(200, firstFinalise(f, "rot", "rot [unk]"))
        f.reset()
        // The same kind of half, or talk before it, is not a join.
        assertEquals(null, firstFinalise(f, "punkt", "punkt", "punkt", "punkt"))
        f.reset()
        assertEquals(null, firstFinalise(f, "ja blau", "ja blau", "ja blau", "ja blau"))
    }

    @Test
    fun `two halves with noise between in one partial are finalised`() {
        val (f, _) = joined(Language.EN)
        assertEquals(300 + EarlyFinalizer.POINT_STABLE_MS, firstFinalise(f, "point", "point [unk]", "point [unk] blue", "point [unk] blue", "point [unk] blue", "point [unk] blue"))
    }

    @Test
    fun `without a half nothing changes for a lone team word`() {
        val (f, _) = joined()
        assertEquals(null, firstFinalise(f, "blau", "blau", "blau", "blau", "blau [unk]"))
    }

    @Test
    fun `the partial result json is read`() {
        assertEquals("punkt blau", VoskResult.parsePartial("{\n  \"partial\" : \"punkt blau\"\n}"))
        assertEquals("", VoskResult.parsePartial("""{"partial" : ""}"""))
        assertEquals("zurück", VoskResult.parsePartial("""{"partial":"zurück"}"""))
        assertEquals(null, VoskResult.parsePartial("""{"text" : "x"}"""))
    }
}
