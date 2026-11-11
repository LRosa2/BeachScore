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

    /** Feeds 16-bit mono PCM; returns a verdict when Vosk ends an utterance, null otherwise. */
    fun accept(samples: ShortArray, count: Int): Heard? =
        if (recognizer.acceptWaveForm(samples, count)) parse(recognizer.result) else null

    /** Flushes what is left at the end of a stream (test files). */
    fun finish(): Heard? = parse(recognizer.finalResult)

    /** Drops everything heard so far (echo guard ended). */
    fun reset() = recognizer.reset()

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
