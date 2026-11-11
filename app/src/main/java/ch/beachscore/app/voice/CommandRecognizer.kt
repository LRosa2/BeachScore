package ch.beachscore.app.voice

import ch.beachscore.app.score.Language
import org.vosk.Model
import org.vosk.Recognizer

/**
 * A Vosk recogniser restricted to the command grammar of one language, with word confidences,
 * whose final results go straight through [CommandParser]. Shared by [VoiceInput] (microphone)
 * and the instrumented recognition test (WAV files), so both exercise the same code.
 */
class CommandRecognizer(
    model: Model,
    private val language: Language,
    private val minConfidence: Double,
    sampleRate: Float = SAMPLE_RATE.toFloat(),
) : AutoCloseable {

    private val recognizer = Recognizer(model, sampleRate, CommandParser.grammarJson(language)).apply {
        setWords(true)
    }

    private val early = EarlyFinalizer(language)
    private var fed = 0L

    /**
     * Feeds 16-bit mono PCM; returns a verdict when the utterance ends, null otherwise. It ends
     * at a pause (Vosk's endpoint) or, without one, as soon as [EarlyFinalizer] sees a command
     * in the partial result; the recogniser then starts a new utterance.
     */
    fun accept(samples: ShortArray, count: Int): Heard? {
        fed += count
        if (recognizer.acceptWaveForm(samples, count)) {
            early.reset()
            return parse(recognizer.result)
        }
        val partial = VoskResult.parsePartial(recognizer.partialResult) ?: return null
        if (!early.shouldFinalize(partial, fed)) return null
        early.reset()
        return parse(recognizer.finalResult)
    }

    /** Flushes what is left at the end of a stream (test files). */
    fun finish(): Heard? = parse(recognizer.finalResult)

    /** Drops everything heard so far (echo guard ended). */
    fun reset() {
        early.reset()
        recognizer.reset()
    }

    /** Last raw JSON, for logging and for capturing test fixtures. */
    var lastJson: String? = null
        private set

    private fun parse(json: String): Heard? {
        lastJson = json
        val result = VoskResult.parse(json) ?: return null
        return CommandParser.parse(result, language, minConfidence)
    }

    override fun close() = recognizer.close()

    companion object {
        /** The small Vosk models are trained on 16 kHz audio. */
        const val SAMPLE_RATE = 16_000
    }
}
