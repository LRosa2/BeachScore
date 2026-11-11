package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.Language

/**
 * Decides when to end an utterance without waiting for silence. Vosk only gives a final result
 * (with word confidences) after a pause; at a court the noise never stops. So the partial
 * result is watched: once it contains a command and either something follows it or it has
 * stayed the same for a moment, the recogniser is asked for its final result now. Pure.
 *
 * Undo waits longer than a point: a single word is often the start of an ordinary sentence
 * ("zurück zum Auto"), and the words after it make it an undo inside speech, which
 * [CommandParser] rejects.
 */
class EarlyFinalizer(private val language: Language, private val sampleRate: Int = CommandRecognizer.SAMPLE_RATE) {

    private var last: String? = null
    private var since = 0L

    /** [partial] is the current partial text, [samples] the audio fed so far (any origin). */
    fun shouldFinalize(partial: String, samples: Long): Boolean {
        if (partial != last) {
            last = partial
            since = samples
        }
        val tokens = partial.lowercase().split(' ').filter { it.isNotEmpty() }
        val matches = CommandParser.find(tokens, language)
        if (matches.isEmpty()) return false
        // Something follows the command, or two different ones: waiting cannot change the outcome.
        if (matches.maxOf { it.last } < tokens.size - 1 || matches.map { it.command }.distinct().size > 1) return true
        val wait = if (matches.last().command == ScoreCommand.Undo) UNDO_STABLE_MS else POINT_STABLE_MS
        return (samples - since) * 1000 >= wait.toLong() * sampleRate
    }

    fun reset() {
        last = null
        since = 0
    }

    companion object {
        /** How long a partial ending in a point command must stay unchanged. */
        const val POINT_STABLE_MS = 200

        /** The same for a partial ending in the undo word. */
        const val UNDO_STABLE_MS = 600
    }
}
