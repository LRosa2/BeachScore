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
 * stays above 0.66. Raise towards STRICT if wrong points appear at the court, lower to RELAXED
 * if clear commands are shown as "low confidence".
 */
enum class VoiceSensitivity(val minConfidence: Double) {
    STRICT(0.95),
    NORMAL(0.85),
    RELAXED(0.70),
}

/** Why a heard phrase did not score. */
enum class RejectReason {
    /** Not exactly one command phrase (contains [unk], extra or missing words). */
    UNKNOWN,
    LOW_CONFIDENCE,
    /** Heard while the app's own announcement was playing (or just after). */
    ECHO_GUARD,
    /** A valid point during the tap lock. */
    LOCKED,
    /** A point while the set-won overlay is shown. */
    SET_WON,
    NOTHING_TO_UNDO,
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
)
