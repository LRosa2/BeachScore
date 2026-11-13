package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `both word orders in both languages`() {
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("blue" to 1.0, "point" to 1.0))))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(json("red" to 1.0, "point" to 0.9))))
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("blau" to 1.0, "punkt" to 1.0), Language.DE)))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(json("rot" to 1.0, "punkt" to 1.0), Language.DE)))
    }

    @Test
    fun `unknown words alone are not a command`() {
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("[unk]" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("[unk]" to 1.0, "[unk]" to 0.4))))
    }

    @Test
    fun `a command is spotted between other sounds and words`() {
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("point" to 1.0, "blue" to 1.0, "[unk]" to 0.6))))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(json("[unk]" to 0.7, "point" to 1.0, "red" to 1.0))))
        assertEquals(
            ScoreCommand.TeamPoint(Team.BLUE),
            command(parse(json("[unk]" to 0.5, "[unk]" to 1.0, "blau" to 0.97, "punkt" to 1.0, "[unk]" to 0.3), Language.DE)),
        )
        // A stray command word next to the command does not matter.
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("point" to 0.4, "point" to 1.0, "blue" to 1.0))))
    }

    @Test
    fun `only the command words need the confidence, not the words around them`() {
        val heard = parse(json("[unk]" to 0.31, "punkt" to 0.99, "rot" to 0.95, "[unk]" to 0.2), Language.DE)
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(heard))
        assertEquals(0.95, heard!!.minConfidence!!, 1e-9)
        assertEquals(RejectReason.LOW_CONFIDENCE, reason(parse(json("[unk]" to 1.0, "punkt" to 1.0, "rot" to 0.7), Language.DE)))
    }

    @Test
    fun `command words that are not adjacent are not a command`() {
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("point" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("blue" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("point" to 1.0, "[unk]" to 1.0, "blue" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("blau" to 1.0, "[unk]" to 0.9, "[unk]" to 0.9, "punkt" to 1.0), Language.DE)))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("blue" to 1.0, "red" to 1.0))))
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("point" to 1.0, "point" to 1.0))))
    }

    @Test
    fun `the same command said twice counts once`() {
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("point" to 1.0, "blue" to 1.0, "point" to 1.0, "blue" to 1.0))))
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("punkt" to 1.0, "blau" to 1.0, "punkt" to 1.0), Language.DE)))
        assertEquals(ScoreCommand.Undo, command(parse(json("undo" to 1.0, "undo" to 1.0))))
    }

    @Test
    fun `different commands in one result give none`() {
        assertEquals(RejectReason.MULTIPLE, reason(parse(json("point" to 1.0, "blue" to 1.0, "point" to 1.0, "red" to 1.0))))
        // "blau punkt rot": blue and red share the word "punkt".
        assertEquals(RejectReason.MULTIPLE, reason(parse(json("blau" to 1.0, "punkt" to 1.0, "rot" to 1.0), Language.DE)))
        assertEquals(RejectReason.MULTIPLE, reason(parse(json("zurück" to 1.0, "[unk]" to 0.5, "punkt" to 1.0, "rot" to 1.0), Language.DE)))
        assertEquals(RejectReason.MULTIPLE, reason(parse(json("undo" to 1.0, "point" to 1.0, "red" to 1.0))))
    }

    @Test
    fun `undo counts only with nothing but noise around it`() {
        for (s in VoiceSensitivity.entries) {
            assertEquals(ScoreCommand.Undo, command(parse(json("zurück" to s.minConfidence), Language.DE, s)))
            assertEquals(ScoreCommand.Undo, command(parse(json("[unk]" to 0.4, "zurück" to 1.0, "[unk]" to 1.0), Language.DE, s)))
            // Measured: the real word in ordinary sentences is heard with confidence 1.00.
            assertEquals(RejectReason.IN_SPEECH, reason(parse(json("der" to 1.0, "ball" to 1.0, "kann" to 1.0, "zurück" to 1.0, "über" to 1.0), Language.DE, s)))
            assertEquals(RejectReason.IN_SPEECH, reason(parse(json("zurück" to 1.0, "zum" to 1.0, "auto" to 1.0), Language.DE, s)))
            assertEquals(RejectReason.IN_SPEECH, reason(parse(json("can" to 1.0, "you" to 1.0, "undo" to 1.0), sensitivity = s)))
        }
    }

    @Test
    fun `a point after a long stretch of talk is talk`() {
        val max = CommandParser.MAX_WORDS_BEFORE_POINT
        val before = List(max) { "der" to 1.0 }.toTypedArray()
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json(*before, "punkt" to 1.0, "blau" to 1.0), Language.DE)))
        assertEquals(RejectReason.IN_SPEECH, reason(parse(json(*before, "ja" to 1.0, "punkt" to 1.0, "blau" to 1.0), Language.DE)))
        // Noise does not count as talk, and words after the command do not matter.
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(json(*before, "[unk]" to 1.0, "[unk]" to 1.0, "red" to 1.0, "point" to 1.0, "nice" to 1.0, "one" to 1.0, "two" to 1.0, "three" to 1.0, "four" to 1.0, "five" to 1.0))))
    }

    @Test
    fun `the heard line shows a spotted command with dots for the rest`() {
        assertEquals("point blue", parse(json("point" to 1.0, "blue" to 1.0))!!.display)
        assertEquals("… punkt blau", parse(json("[unk]" to 1.0, "punkt" to 1.0, "blau" to 1.0), Language.DE)!!.display)
        assertEquals("rot punkt …", parse(json("rot" to 1.0, "punkt" to 1.0, "[unk]" to 1.0), Language.DE)!!.display)
        assertEquals("… undo …", parse(json("[unk]" to 1.0, "undo" to 1.0, "[unk]" to 1.0))!!.display)
        // Not a command: the whole text, as before.
        assertEquals("[unk] point [unk]", parse(json("[unk]" to 1.0, "point" to 1.0, "[unk]" to 1.0))!!.display)
        // Low confidence still shows where the command was.
        assertEquals("… point red", parse(json("[unk]" to 1.0, "point" to 1.0, "red" to 0.5))!!.display)
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
        // Captured from vosk-android 0.3.75 + vosk-model-small-de-0.15 (VoiceRecognitionTest, de__tts__undo).
        val recorded = """{
  "result" : [{
      "conf" : 1.000000,
      "end" : 0.930000,
      "start" : 0.300000,
      "word" : "zurück"
    }],
  "text" : "zurück"
}"""
        assertEquals(ScoreCommand.Undo, command(parse(recorded, Language.DE)))
        // The same with \u escapes, which other Vosk builds emit.
        val escaped = """{"result":[{"conf":1.0,"word":"zurück"}],"text":"zurück"}"""
        assertEquals(ScoreCommand.Undo, command(parse(escaped, Language.DE)))
    }

    @Test
    fun `measured near misses are rejected at every sensitivity`() {
        // From VoiceRecognitionTest with noise: "point green" heard as "point blue" 0.62,
        // "Punkt Grün" as "punkt rot" 0.66, our own "Satz Blau, …" as "zurück" 0.61.
        for (s in VoiceSensitivity.entries) {
            assertEquals(RejectReason.LOW_CONFIDENCE, reason(parse(json("point" to 1.0, "blue" to 0.62), sensitivity = s)))
            assertEquals(RejectReason.LOW_CONFIDENCE, reason(parse(json("punkt" to 1.0, "rot" to 0.66), Language.DE, s)))
            assertEquals(RejectReason.LOW_CONFIDENCE, reason(parse(json("zurück" to 0.61), Language.DE, s)))
        }
    }

    @Test
    fun `decoys are ordinary words, never command words or their homophones`() {
        val commandWords = mapOf(
            Language.EN to setOf("point", "points", "blue", "red", "undo", "blew", "read"),
            Language.DE to setOf("punkt", "punk", "blau", "rot", "zurück"),
        )
        for (language in Language.entries) {
            val decoys = CommandParser.decoys(language)
            assert(decoys.size > 100) { "$language: ${decoys.size}" }
            assertEquals(decoys.size, decoys.toSet().size, "$language duplicates")
            assertEquals(emptySet<String>(), decoys.toSet() intersect commandWords.getValue(language))
            assert(decoys.all { it.matches(Regex("[a-zäöüß]+")) }) { language }
        }
    }

    @Test
    fun `decoy words around a command are other words`() {
        val heard = parse(json("der" to 1.0, "punkt" to 1.0, "blau" to 1.0, "war" to 0.8), Language.DE)
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(heard))
        assertEquals("… punkt blau …", heard!!.display)
        assertEquals(RejectReason.UNKNOWN, reason(parse(json("die" to 1.0, "blaue" to 1.0, "mannschaft" to 1.0), Language.DE)))
    }

    @Test
    fun `malformed json is not a result`() {
        assertNull(VoskResult.parse("not json"))
        assertNull(VoskResult.parse("""{"result": [] }"""))
    }

    @Test
    fun `grammar lists the phrases of one language plus unknown`() {
        assertEquals(listOf("point blue", "blue point", "point red", "red point", "undo"), CommandParser.grammar(Language.EN).take(5))
        assertEquals(listOf("punkt blau", "blau punkt", "punkt rot", "rot punkt", "zurück"), CommandParser.grammar(Language.DE).take(5))
        for (language in Language.entries) {
            assertEquals("[unk]", CommandParser.grammar(language).last())
            assertEquals(CommandParser.decoys(language), CommandParser.grammar(language).drop(8).dropLast(1))
            assertEquals(CommandParser.decoys(language), CommandParser.grammar(CommandWords.defaults(language), halves = false).drop(5).dropLast(1))
        }
        assert(CommandParser.grammarJson(Language.DE).startsWith("""["punkt blau","blau punkt","punkt rot","rot punkt","zurück","punkt","blau","rot","der","""))
    }

    @Test
    fun `since 3_3_1 the grammar holds each point and team word on its own - so a half can be heard`() {
        assertEquals(listOf("point", "blue", "red"), CommandParser.grammar(Language.EN).subList(5, 8))
        val grammar = CommandParser.grammar(custom)
        assertEquals(listOf("tor", "himmel", "azur", "feuer"), grammar.subList(7, 11))
        // Not undo twice, not the alike variants.
        assertEquals(1, CommandParser.grammar(Language.DE).count { it == "zurück" })
        assertFalse("punk" in CommandParser.grammar(Language.DE))
    }

    @Test
    fun `the alike default variants are heard through their base word, never as decoys`() {
        // As separate grammar entries "points"/"punk" split the confidence of a clear "point"
        // (0.50-0.64 instead of 1.00, VoiceSpottingTest); a closed grammar hears them as the base word.
        assertEquals(setOf("punk"), CommandWords.defaults(Language.DE).alike)
        assertEquals(setOf("points"), CommandWords.defaults(Language.EN).alike)
        for (language in Language.entries) {
            val grammar = CommandParser.grammar(language)
            for (variant in CommandWords.defaults(language).alike) {
                assertFalse(grammar.any { it == variant || it.split(' ').contains(variant) }, variant)
            }
        }
        // A user's point words are all their own entries.
        val custom = CommandWords.of(Language.EN, CustomWords(point = listOf("point", "points")))
        assertEquals(emptySet<String>(), custom.alike)
        assertTrue("points blue" in CommandParser.grammar(custom))
    }

    @Test
    fun `the new default point variants score`() {
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("punk" to 1.0, "blau" to 1.0), Language.DE)))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(json("rot" to 1.0, "punk" to 1.0), Language.DE)))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parse(json("points" to 1.0, "red" to 1.0))))
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parse(json("blue" to 1.0, "points" to 1.0))))
    }

    private val custom = CommandWords.of(Language.DE, CustomWords(blue = listOf("himmel", "azur"), red = listOf("feuer"), point = listOf("tor")))

    private fun parseCustom(vararg words: Pair<String, Double>) =
        CommandParser.parse(VoskResult.parse(json(*words))!!, custom, VoiceSensitivity.NORMAL.minConfidence)

    @Test
    fun `custom words score in both orders, every alternative`() {
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parseCustom("tor" to 1.0, "himmel" to 1.0)))
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parseCustom("azur" to 1.0, "tor" to 1.0)))
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(parseCustom("feuer" to 1.0, "tor" to 0.9)))
        assertEquals(ScoreCommand.Undo, command(parseCustom("zurück" to 1.0)))
    }

    @Test
    fun `replaced default words no longer score`() {
        assertEquals(RejectReason.UNKNOWN, reason(parseCustom("punkt" to 1.0, "blau" to 1.0)))
        assertEquals(RejectReason.UNKNOWN, reason(parseCustom("rot" to 1.0, "punkt" to 1.0)))
        assertEquals(RejectReason.UNKNOWN, reason(parseCustom("tor" to 1.0, "rot" to 1.0)))
    }

    @Test
    fun `spotting and the inside-speech rules work with custom words`() {
        val heard = parseCustom("[unk]" to 1.0, "tor" to 1.0, "feuer" to 1.0, "[unk]" to 1.0)
        assertEquals(ScoreCommand.TeamPoint(Team.RED), command(heard))
        assertEquals("… tor feuer …", heard!!.display)
        assertEquals(RejectReason.MULTIPLE, reason(parseCustom("tor" to 1.0, "himmel" to 1.0, "tor" to 1.0, "feuer" to 1.0)))
        assertEquals(ScoreCommand.TeamPoint(Team.BLUE), command(parseCustom("tor" to 1.0, "himmel" to 1.0, "himmel" to 1.0, "tor" to 1.0)))
        assertEquals(
            RejectReason.IN_SPEECH,
            reason(parseCustom("der" to 1.0, "ball" to 1.0, "war" to 1.0, "im" to 1.0, "netz" to 1.0, "tor" to 1.0, "himmel" to 1.0)),
        )
        assertEquals(RejectReason.LOW_CONFIDENCE, reason(parseCustom("tor" to 0.5, "himmel" to 1.0)))
        assertEquals(RejectReason.UNKNOWN, reason(parseCustom("tor" to 1.0, "[unk]" to 1.0, "himmel" to 1.0)))
    }

    @Test
    fun `grammar of a custom set - with its phrases and the replaced defaults as decoys`() {
        val grammar = CommandParser.grammar(custom)
        assertEquals(listOf("tor himmel", "himmel tor", "tor azur", "azur tor", "tor feuer", "feuer tor", "zurück"), grammar.take(7))
        val decoys = grammar.drop(11).dropLast(1)
        assertTrue("punkt" in decoys && "punk" in decoys && "blau" in decoys && "rot" in decoys)
        assertFalse("tor" in decoys || "himmel" in decoys || "feuer" in decoys)
        // "netz" is a default decoy; as a command word it leaves the decoys.
        val net = CommandParser.grammar(CommandWords.of(Language.DE, CustomWords(blue = listOf("netz"))))
        assertEquals(1, net.count { it == "netz" }) // the half, not a decoy as well
        assertTrue("netz punkt" in net)
        assertEquals(decoys.size, decoys.toSet().size)
        assertEquals("[unk]", grammar.last())
    }
}
