package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandParserTest {

    /** Builds a Vosk final result as the recogniser prints it (setWords(true)). */
    private fun json(vararg words: Pair<String, Double>): String {
        val items = words.mapIndexed { i, (w, c) ->
            """{
      "conf" : ${"%.6f".format(java.util.Locale.ROOT, c)},
      "end" : ${i + 1}.000000,
      "start" : $i.500000,
      "word" : "$w"
    }"""
        }
        return """{
  "result" : [${items.joinToString(", ")}],
  "text" : "${words.joinToString(" ") { it.first }}"
}"""
    }

    private fun parse(json: String, language: Language = Language.EN, sensitivity: VoiceSensitivity = VoiceSensitivity.NORMAL) =
        CommandParser.parse(VoskResult.parse(json)!!, language, sensitivity.minConfidence)

    private fun command(heard: Heard?) = (heard?.verdict as? Verdict.Accepted)?.command
    private fun reason(heard: Heard?) = (heard?.verdict as? Verdict.Rejected)?.reason

    @Test
    fun `english commands`() {
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("point" to 1.0, "blue" to 0.98))))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(json("point" to 0.99, "red" to 1.0))))
        assertEquals(ScoreCommand.Undo, command(parse(json("undo" to 1.0))))
    }

    @Test
    fun `german commands`() {
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("punkt" to 1.0, "blau" to 1.0), Language.DE)))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(json("punkt" to 1.0, "rot" to 0.9), Language.DE)))
        assertEquals(ScoreCommand.Undo, command(parse(json("zurück" to 1.0), Language.DE)))
    }

    @Test
    fun `phrases of the other language are not commands`() {
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("point" to 1.0, "blue" to 1.0), Language.DE)))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("zurück" to 1.0), Language.EN)))
    }

    @Test
    fun `unknown words reject the whole result`() {
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("[unk]" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("point" to 1.0, "blue" to 1.0, "[unk]" to 0.6))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("[unk]" to 0.7, "point" to 1.0, "red" to 1.0))))
    }

    @Test
    fun `incomplete or extra words reject`() {
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("point" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("blue" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("blue" to 1.0, "point" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("point" to 1.0, "blue" to 1.0, "point" to 1.0, "red" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("undo" to 1.0, "undo" to 1.0))))
    }

    @Test
    fun `every word must reach the threshold`() {
        val result = parse(json("point" to 0.97, "blue" to 0.62))
        assertEquals(RejectReason.LOW_CONFIDENCE, reason(result))
        assertEquals(0.62, result!!.minConfidence!!, 1e-9)
        assertEquals("point blue", result.text)
    }

    @Test
    fun `sensitivity levels move the threshold`() {
        val words = json("point" to 1.0, "red" to 0.7)
        assertEquals(RejectReason.LOW_CONFIDENCE, reason(parse(words, sensitivity = VoiceSensitivity.STRICT)))
        assertEquals(RejectReason.LOW_CONFIDENCE, reason(parse(words, sensitivity = VoiceSensitivity.NORMAL)))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(words, sensitivity = VoiceSensitivity.RELAXED)))
        assert(VoiceSensitivity.STRICT.minConfidence > VoiceSensitivity.NORMAL.minConfidence)
        assert(VoiceSensitivity.NORMAL.minConfidence > VoiceSensitivity.RELAXED.minConfidence)
    }

    @Test
    fun `threshold is inclusive`() {
        val t = VoiceSensitivity.NORMAL.minConfidence
        assertEquals(ScoreCommand.Undo, command(parse(json("undo" to t))))
    }

    @Test
    fun `empty final results are silence and produce nothing`() {
        assertNull(CommandParser.parse(VoskResult.parse("""{"text" : ""}""")!!, Language.EN, 0.5))
        assertNull(CommandParser.parse(VoskResult.parse("{\n  \"text\" : \"\"\n}")!!, Language.EN, 0.5))
    }

    @Test
    fun `result without word confidences is rejected as low confidence`() {
        val heard = CommandParser.parse(VoskResult.parse("""{"text" : "point blue"}""")!!, Language.EN, 0.5)
        assertEquals(RejectReason.LOW_CONFIDENCE, reason(heard))
    }

    @Test
    fun `real recogniser output with unicode and escapes`() {
        // Layout as printed by the recogniser with setWords(true); raw UTF-8 or \u escapes.
        val recorded = """{
  "result" : [{
      "conf" : 1.000000,
      "end" : 1.410000,
      "start" : 0.900000,
      "word" : "zurück"
    }],
  "text" : "zurück"
}"""
        assertEquals(ScoreCommand.Undo, command(parse(recorded, Language.DE)))
        val escaped = """{"result":[{"conf":1.0,"word":"zurück"}],"text":"zurück"}"""
        assertEquals(ScoreCommand.Undo, command(parse(escaped, Language.DE)))
    }

    @Test
    fun `malformed json is not a result`() {
        assertNull(VoskResult.parse("not json"))
        assertNull(VoskResult.parse("""{"result": [] }"""))
    }

    @Test
    fun `grammar lists the phrases of one language plus unknown`() {
        assertEquals(listOf("point blue", "point red", "undo", "[unk]"), CommandParser.grammar(Language.EN))
        assertEquals(listOf("punkt blau", "punkt rot", "zurück", "[unk]"), CommandParser.grammar(Language.DE))
        assertEquals("""["punkt blau","punkt rot","zurück","[unk]"]""", CommandParser.grammarJson(Language.DE))
    }
}
