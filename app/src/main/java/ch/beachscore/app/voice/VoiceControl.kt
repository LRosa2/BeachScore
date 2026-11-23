package ch.beachscore.app.voice

import ch.beachscore.app.score.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface VoiceStatus {
    data object Off : VoiceStatus

    /** Copying the speech model to internal storage on first use. */
    data class Preparing(val progress: Float) : VoiceStatus
    data object Listening : VoiceStatus

    /** Listening, but results are ignored because the app's own announcement is playing. */
    data object Muted : VoiceStatus
    data class Failed(val reason: Reason) : VoiceStatus {
        enum class Reason { MODEL, MICROPHONE }
    }
}

/** What the view model needs from the voice input: results with verdicts, status, on/off. */
interface VoiceControl {
    /** Every non-empty final result, already checked by [CommandParser] and the echo guard. */
    val heard: Flow<Heard>
    val status: StateFlow<VoiceStatus>

    /** Connected audio inputs (all kinds; [Microphones.selectable] picks the offered ones). */
    val microphones: StateFlow<List<InputDevice>>

    /** The input the recorder is actually routed to while listening, else null. */
    val activeMicrophone: StateFlow<InputDevice?>

    /**
     * Starts (or reconfigures) listening; idempotent for the same arguments. A new
     * [microphone] (null: automatic) is applied without reloading the model. [maxPauseMs] is
     * the longest pause between the two words of a point command ([HalfJoiner]).
     */
    fun start(
        language: Language,
        minConfidence: Double,
        microphone: MicChoice? = null,
        words: CommandWords = CommandWords.defaults(language),
        maxPauseMs: Long = HalfJoiner.DEFAULT_PAUSE_MS,
    )
    fun stop()

    /**
     * The [words] the speech model of [language] does not know, in order; null when the model
     * cannot be loaded. Loads the model (about a second); call from a coroutine.
     */
    suspend fun unknownWords(language: Language, words: List<String>): List<String>?
}
