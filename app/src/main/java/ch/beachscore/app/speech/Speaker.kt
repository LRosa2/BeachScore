package ch.beachscore.app.speech

import ch.beachscore.app.score.Language
import kotlinx.coroutines.flow.StateFlow

enum class SpeechAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE }

/** Speaks short phrases. A new phrase interrupts any phrase still being spoken. */
interface Speaker {
    /** Whether speech works for the language last passed to [prepare]. */
    val availability: StateFlow<SpeechAvailability>

    /** Selects the voice early so the first announcement is not delayed. */
    fun prepare(language: Language)

    fun speak(text: String, language: Language)
}
