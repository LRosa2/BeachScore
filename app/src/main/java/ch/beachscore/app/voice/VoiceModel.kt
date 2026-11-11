package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand

/**
 * "Voice sensitivity" setting. Every word of a command must reach [minConfidence] (Vosk
 * per-word confidence, 0..1). All tuning lives here.
 *
 * With a closed grammar Vosk reports ~1.0 for clearly spoken commands; values drop when the
 * audio is faint, noisy or only partly matches. See VoiceRecognitionTest for measured values.
 */
enum class VoiceSensitivity(val minConfidence: Double) {
    STRICT(0.92),
    NORMAL(0.80),
    RELAXED(0.60),
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
