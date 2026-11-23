package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand

/**
 * "Voice sensitivity" setting. Every word of a command must reach [minConfidence] (Vosk
 * per-word confidence, 0..1). All tuning lives here.
 *
 * Measured with VoiceRecognitionTest (real Vosk, bundled small models, 68 speech samples, clean
 * and 20 dB quieter with noise): correctly heard commands score 1.00 (one quiet "undo" 0.60).
 * Near misses score 0.61-0.66: "point green" -> "point blue" 0.62, "Punkt Gruen" -> "punkt rot"
 * 0.66, and our own "Satz Blau, fuenfzehn, zwoelf" -> "zurueck" 0.61. Even RELAXED therefore
 * stays above 0.66. Since 3.5.0 the user can go lower (down to 0.30, [VoiceThreshold]): below
 * about 0.66 such near misses can score.
 */
enum class VoiceSensitivity(val minConfidence: Double) {
    STRICT(0.95),
    NORMAL(0.85),
    RELAXED(0.70),
}

/**
 * 3.5.0: "Minimum confidence" is a number the user sets with a slider; the [VoiceSensitivity]
 * levels remain as names for it (legend, migration). Kept in whole hundredths so a stored
 * 0.85 is exactly the double 0.85 and a word scored 0.85 passes.
 */
object VoiceThreshold {
    const val MIN_PCT = 30
    const val MAX_PCT = 95
    const val STEP_PCT = 5
    const val DEFAULT_PCT = 85

    fun toConfidence(pct: Int): Double = pct / 100.0

    /** Clamped to the range and rounded to the nearest step. */
    fun sanitize(pct: Int): Int {
        val clamped = pct.coerceIn(MIN_PCT, MAX_PCT)
        return (Math.round(clamped / STEP_PCT.toDouble()) * STEP_PCT).toInt().coerceIn(MIN_PCT, MAX_PCT)
    }

    /** The 3.4 setting stored a level name: STRICT/NORMAL/RELAXED map to 95/85/70. */
    fun fromLegacy(name: String?): Int? =
        VoiceSensitivity.entries.firstOrNull { it.name == name }?.let { Math.round(it.minConfidence * 100).toInt() }

    fun label(pct: Int): String = String.format(java.util.Locale.ROOT, "%.2f", toConfidence(pct))
}

/** Why a heard phrase did not score. */
enum class RejectReason {
    /** No command phrase in it (only [unk], or command words that are not adjacent). */
    UNKNOWN,
    /** Two different commands in one result ("punkt blau, punkt rot"): none is acted on. */
    MULTIPLE,
    /** Undo with words around it, or a point after a long stretch of talk (see [CommandParser]). */
    IN_SPEECH,
    /** A command, but one of its words scored below the minimum confidence. */
    LOW_CONFIDENCE,
    /** A command in the text, but Vosk gave no matching word list to score it (3.5.0). */
    NO_WORD_SCORES,
    /** Heard while the app's own announcement was playing (or just after). */
    ECHO_GUARD,
    /** A valid point during the tap lock. */
    LOCKED,
    /** A point while the set-won overlay is shown. */
    SET_WON,
    NOTHING_TO_UNDO,
    /** Half a point command ("punkt"); waiting for the other word (see [HalfJoiner]). */
    INCOMPLETE,
    /** The two halves of a point command, but the pause between them was too long. */
    PAUSE_TOO_LONG,
}

sealed interface Verdict {
    data class Accepted(val command: ScoreCommand) : Verdict
    data class Rejected(val reason: RejectReason) : Verdict
}

/** One final recognition result and what became of it; drives the diagnostics line. */
data class Heard(
    val text: String,
    /** Lowest per-word confidence, or null when the result had no word list. */
    val minConfidence: Double?,
    val verdict: Verdict,
    /** What the diagnostics line shows: the spotted command, with "…" where other words were. */
    val display: String = text,
)
