package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.Team
import java.util.Locale

/**
 * Joins the two words of a point command across a pause (3.3.1): "Punkt … Blau". Vosk ends
 * the utterance at a pause, so each half arrives as its own final result; or, under
 * continuous noise, both arrive in one result with `[unk]` between them. Pure; no Android.
 *
 * A *half* is a point word or a team word of [words] (not undo). A half with nothing but
 * `[unk]` after it is remembered ([pending]); the matching other half, again after nothing
 * but `[unk]`, completes a point command if the pause between them (end of the first word to
 * the start of the second, Vosk word times) is at most [maxPauseMs]. Any other word, a full
 * command, a completed or rejected join and [reset] drop the remembered half.
 *
 * Per half the rules of [CommandParser] apply: at most [CommandParser.MAX_WORDS_BEFORE_POINT]
 * words of talk before the first half in its result (the second can only have `[unk]` before
 * it); the confidence of a join is the lower of the two, against [minConfidence].
 */
class HalfJoiner(
    private val words: CommandWords,
    private val maxPauseMs: Long,
    private val minConfidence: Double,
) {
    /** A remembered half: [team] null for a point word. [end] in seconds of audio. */
    data class Half(val word: String, val team: Team?, val end: Double, val confidence: Double) {
        val isPoint: Boolean get() = team == null
    }

    var pending: Half? = null
        private set

    fun reset() {
        pending = null
    }

    /**
     * Combines a final [result] with the remembered half. [parsed] is [CommandParser]'s verdict
     * for [result] alone; it is returned unchanged unless a half is involved.
     */
    fun join(result: VoskResult, parsed: Heard?): Heard? {
        parsed ?: return null // silence: keep waiting
        val tokens = result.text.lowercase().split(' ').filter { it.isNotEmpty() }
        if (CommandParser.find(tokens, words).isNotEmpty() || result.words.size != tokens.size ||
            result.words.any { it.start == null || it.end == null }
        ) {
            pending = null
            return parsed
        }
        var current = pending
        var talk = 0
        var lastHalfHere: Half? = null
        for ((i, word) in result.words.withIndex()) {
            val token = tokens[i]
            if (token == CommandParser.UNKNOWN_WORD) continue
            if (!isHalf(token)) {
                talk++
                current = null
                lastHalfHere = null
                continue
            }
            val half = Half(token, teamOf(token), word.end!!, word.confidence)
            val first = current
            if (first != null && first.isPoint != half.isPoint) {
                val pause = word.start!! - first.end
                val confidence = minOf(first.confidence, half.confidence)
                val display = "${first.word} ‥ ${half.word} (${String.format(Locale.ROOT, "%.1f", pause)} s)"
                val verdict = when {
                    pause * 1000 > maxPauseMs + EPSILON_MS -> Verdict.Rejected(RejectReason.PAUSE_TOO_LONG)
                    confidence < minConfidence -> Verdict.Rejected(RejectReason.LOW_CONFIDENCE)
                    else -> Verdict.Accepted(ScoreCommand.TeamPoint(first.team ?: half.team!!))
                }
                // Too late: this half is a first half in its own right.
                pending = if (verdict == Verdict.Rejected(RejectReason.PAUSE_TOO_LONG)) half else null
                return Heard(result.text, confidence, verdict, display)
            }
            current = if (talk <= CommandParser.MAX_WORDS_BEFORE_POINT) half else null
            lastHalfHere = current
        }
        pending = current
        val waiting = lastHalfHere ?: return parsed
        return parsed.copy(minConfidence = waiting.confidence, verdict = Verdict.Rejected(RejectReason.INCOMPLETE), display = "${waiting.word} ‥")
    }

    /**
     * For the early finalizer, on a partial result without timestamps: the index of the token
     * that completes a join with [pending] or with a half earlier in [tokens], or null.
     */
    fun completes(tokens: List<String>): Int? {
        var current: Boolean? = pending?.isPoint
        var talk = 0
        for ((i, token) in tokens.withIndex()) {
            if (token == CommandParser.UNKNOWN_WORD) continue
            if (!isHalf(token)) {
                talk++
                current = null
                continue
            }
            val isPoint = teamOf(token) == null
            if (current != null && current != isPoint) return i
            current = if (talk <= CommandParser.MAX_WORDS_BEFORE_POINT) isPoint else null
        }
        return null
    }

    private fun isHalf(token: String) = token in words.point || teamOf(token) != null

    private fun teamOf(token: String): Team? = when (token) {
        in words.blue -> Team.BLUE
        in words.red -> Team.RED
        else -> null
    }

    companion object {
        /** "Pause between words" setting: the choices and the default. */
        val PAUSE_CHOICES_MS = listOf(1000L, 2000L, 3000L)
        const val DEFAULT_PAUSE_MS = 2000L

        /** Vosk times are printed with a few decimals; 2.0 s may come out as 2.0000001. */
        private const val EPSILON_MS = 1.0
    }
}
