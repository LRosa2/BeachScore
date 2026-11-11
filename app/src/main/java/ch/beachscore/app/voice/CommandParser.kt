package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.Team

/**
 * Turns one Vosk final result into a command or a rejection reason. Pure; no Android.
 *
 * Rules (spec "False-trigger protection"): the whole result must be exactly one command phrase
 * of the active language, with no `[unk]` and no extra words, and every word must reach the
 * confidence threshold.
 */
object CommandParser {

    const val UNKNOWN_WORD = "[unk]"

    private val PHRASES: Map<Language, List<Pair<String, ScoreCommand>>> = mapOf(
        Language.EN to listOf(
            "point blue" to ScoreCommand.TeamPoint(Team.BLUE),
            "point red" to ScoreCommand.TeamPoint(Team.RED),
            "undo" to ScoreCommand.Undo,
        ),
        Language.DE to listOf(
            "punkt blau" to ScoreCommand.TeamPoint(Team.BLUE),
            "punkt rot" to ScoreCommand.TeamPoint(Team.RED),
            "zurück" to ScoreCommand.Undo,
        ),
    )

    /** The closed grammar for the recogniser: the command phrases plus `[unk]`. */
    fun grammar(language: Language): List<String> = PHRASES.getValue(language).map { it.first } + UNKNOWN_WORD

    fun grammarJson(language: Language): String =
        grammar(language).joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

    /** Returns null for an empty result (silence), which is not worth showing. */
    fun parse(result: VoskResult, language: Language, minConfidence: Double): Heard? {
        val text = result.text.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val minConf = result.words.minOfOrNull { it.confidence }

        val tokens = text.split(' ')
        val command = PHRASES.getValue(language).firstOrNull { it.first == text.lowercase() }?.second
        val verdict = when {
            UNKNOWN_WORD in tokens || command == null -> Verdict.Rejected(RejectReason.UNKNOWN)
            minConf == null || result.words.size != tokens.size -> Verdict.Rejected(RejectReason.LOW_CONFIDENCE)
            minConf < minConfidence -> Verdict.Rejected(RejectReason.LOW_CONFIDENCE)
            else -> Verdict.Accepted(command)
        }
        return Heard(text, minConf, verdict)
    }
}
