package ch.beachscore.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 3.5.0: "Minimum confidence" as a number; the old levels map onto it. */
class VoiceThresholdTest {

    @Test
    fun `range, step and default`() {
        assertEquals(30, VoiceThreshold.MIN_PCT)
        assertEquals(95, VoiceThreshold.MAX_PCT)
        assertEquals(5, VoiceThreshold.STEP_PCT)
        assertEquals(85, VoiceThreshold.DEFAULT_PCT)
        assertEquals(0.85, VoiceThreshold.toConfidence(85))
        assertEquals(VoiceSensitivity.NORMAL.minConfidence, VoiceThreshold.toConfidence(VoiceThreshold.DEFAULT_PCT))
    }

    @Test
    fun `the old levels map to their numbers`() {
        assertEquals(95, VoiceThreshold.fromLegacy("STRICT"))
        assertEquals(85, VoiceThreshold.fromLegacy("NORMAL"))
        assertEquals(70, VoiceThreshold.fromLegacy("RELAXED"))
        assertNull(VoiceThreshold.fromLegacy(null))
        assertNull(VoiceThreshold.fromLegacy("LOUD"))
        for (level in VoiceSensitivity.entries) {
            assertEquals(level.minConfidence, VoiceThreshold.toConfidence(VoiceThreshold.fromLegacy(level.name)!!))
        }
    }

    @Test
    fun `values from disk are clamped and snapped to the step`() {
        assertEquals(30, VoiceThreshold.sanitize(0))
        assertEquals(30, VoiceThreshold.sanitize(-40))
        assertEquals(95, VoiceThreshold.sanitize(100))
        assertEquals(55, VoiceThreshold.sanitize(55))
        assertEquals(55, VoiceThreshold.sanitize(56))
        assertEquals(60, VoiceThreshold.sanitize(58))
        assertEquals(70, VoiceThreshold.sanitize(72))
    }

    @Test
    fun `a word at exactly the threshold passes`() {
        // Stored in whole hundredths: 0.85 is the same double as a Vosk 0.85, never 0.8500001.
        for (pct in VoiceThreshold.MIN_PCT..VoiceThreshold.MAX_PCT step VoiceThreshold.STEP_PCT) {
            val t = VoiceThreshold.toConfidence(pct)
            assertEquals(String.format(java.util.Locale.ROOT, "%.2f", t).toDouble(), t)
        }
    }

    @Test
    fun `a heard confidence is cut, not rounded, so a rejection never reads as the threshold`() {
        assertEquals("0.69", VoiceThreshold.shown(0.698))
        assertEquals("0.70", VoiceThreshold.shown(0.70))
        assertEquals("0.62", VoiceThreshold.shown(0.62))
        assertEquals("1.00", VoiceThreshold.shown(1.0))
        assertEquals("0.00", VoiceThreshold.shown(0.0))
    }

    @Test
    fun `the label shows two decimals`() {
        assertEquals("0.55", VoiceThreshold.label(55))
        assertEquals("0.30", VoiceThreshold.label(30))
    }
}
