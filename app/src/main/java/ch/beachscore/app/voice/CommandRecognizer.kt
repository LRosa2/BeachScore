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
    private val words: CommandWords,
    private val minConfidence: Double,
    sampleRate: Float = SAMPLE_RATE.toFloat(),
    /** Longest pause between the two words of a point command; null: no joining (3.3.0). */
    maxPauseMs: Long? = HalfJoiner.DEFAULT_PAUSE_MS,
) : AutoCloseable {

    constructor(model: Model, language: Language, minConfidence: Double, sampleRate: Float = SAMPLE_RATE.toFloat()) :
        this(model, CommandWords.defaults(language), minConfidence, sampleRate)

    private val recognizer = Recognizer(model, sampleRate, CommandParser.grammarJson(words, halves = maxPauseMs != null)).apply {
        setWords(true)
    }

    private val joiner = maxPauseMs?.let { HalfJoiner(words, it, minConfidence) }
    private val early = EarlyFinalizer(words, joiner = joiner)
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

    /** Drops everything heard so far, a remembered half included (echo guard ended). */
    fun reset() {
        early.reset()
        joiner?.reset()
        recognizer.reset()
    }

    /** Last raw JSON, for logging and for capturing test fixtures. */
    var lastJson: String? = null
        private set

    private fun parse(json: String): Heard? {
        lastJson = json
        val result = VoskResult.parse(json) ?: return null
        val heard = CommandParser.parse(result, words, minConfidence)
        return joiner?.join(result, heard) ?: heard
    }

    override fun close() = recognizer.close()

    companion object {
        /** The small Vosk models are trained on 16 kHz audio. */
        const val SAMPLE_RATE = 16_000
    }
}
