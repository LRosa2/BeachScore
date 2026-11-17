package ch.beachscore.app.voice

import ch.beachscore.app.score.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceWordsTest {

    /** [point] null: the field is not given, which means its default words. */
    private fun fields(blue: String = "", red: String = "", point: String? = null) =
        mapOf(WordField.BLUE to blue, WordField.RED to red) + (point?.let { mapOf(WordField.POINT to it) } ?: emptyMap())

    @Test
    fun `defaults per language, with the new point variants`() {
        val de = CommandWords.defaults(Language.DE)
        assertEquals(listOf("blau"), de.blue)
        assertEquals(listOf("rot"), de.red)
        assertEquals(listOf("punkt", "punk"), de.point)
        assertEquals("zurück", de.undo)
        val en = CommandWords.defaults(Language.EN)
        assertEquals(listOf("blue"), en.blue)
        assertEquals(listOf("red"), en.red)
        assertEquals(listOf("point", "points"), en.point)
        assertEquals("undo", en.undo)
    }

    @Test
    fun `empty fields mean the defaults, a filled field replaces them`() {
        assertEquals(CommandWords.defaults(Language.DE), CommandWords.of(Language.DE, CustomWords()))
        val words = CommandWords.of(Language.DE, CustomWords(blue = listOf("himmel", "azur")))
        assertEquals(listOf("himmel", "azur"), words.blue)
        assertEquals(listOf("rot"), words.red)
        assertEquals(listOf("punkt", "punk"), words.point)
        assertEquals(listOf("blau"), words.replaced)
        assertEquals(emptyList<String>(), CommandWords.defaults(Language.DE).replaced)
    }

    @Test
    fun `a field is split at commas, trimmed and lower-cased, and empty entries are ignored`() {
        val result = VoiceWords.check(Language.DE, fields(blue = " Himmel , AZUR,, ", point = "Tor"))
        assertEquals(WordsCheck.Ok(CustomWords(blue = listOf("himmel", "azur"), point = listOf("tor"))), result)
        assertEquals(WordsCheck.Ok(CustomWords()), VoiceWords.check(Language.EN, fields(blue = " , ")))
    }

    @Test
    fun `the same word twice in one field is kept once`() {
        assertEquals(WordsCheck.Ok(CustomWords(red = listOf("feuer"))), VoiceWords.check(Language.DE, fields(red = "feuer, Feuer")))
    }

    @Test
    fun `an entry with more than one word is rejected`() {
        assertEquals(WordsCheck.Failed(WordProblem.NotOneWord("punkt eins")), VoiceWords.check(Language.DE, fields(point = "tor, punkt  eins")))
    }

    @Test
    fun `the undo word is rejected in any field`() {
        assertEquals(WordsCheck.Failed(WordProblem.UndoWord("zurück")), VoiceWords.check(Language.DE, fields(red = "Zurück")))
        assertEquals(WordsCheck.Failed(WordProblem.UndoWord("undo")), VoiceWords.check(Language.EN, fields(point = "undo")))
    }

    @Test
    fun `a word in two fields is rejected, counting the defaults of empty fields`() {
        assertEquals(WordsCheck.Failed(WordProblem.Duplicate("feuer")), VoiceWords.check(Language.DE, fields(blue = "feuer", red = "feuer")))
        // Red is empty, so "rot" is still the red word.
        assertEquals(WordsCheck.Failed(WordProblem.Duplicate("rot")), VoiceWords.check(Language.DE, fields(blue = "rot")))
        assertEquals(WordsCheck.Failed(WordProblem.Duplicate("punkt")), VoiceWords.check(Language.DE, fields(blue = "punkt")))
        // Red replaced, so "rot" is free for blue.
        assertTrue(VoiceWords.check(Language.DE, fields(blue = "rot", red = "feuer")) is WordsCheck.Ok)
    }

    @Test
    fun `characters that are not letters are rejected as not one word`() {
        assertEquals(WordsCheck.Failed(WordProblem.NotOneWord("tor!")), VoiceWords.check(Language.DE, fields(point = "tor!")))
        assertEquals(WordsCheck.Failed(WordProblem.NotOneWord("[unk]")), VoiceWords.check(Language.DE, fields(point = "[unk]")))
    }

    @Test
    fun `custom words are written and read back as one comma separated value`() {
        assertEquals("himmel,azur", VoiceWords.encode(listOf("himmel", "azur")))
        assertEquals(listOf("himmel", "azur"), VoiceWords.decode("himmel,azur"))
        assertEquals(emptyList<String>(), VoiceWords.decode(null))
        assertEquals(emptyList<String>(), VoiceWords.decode(""))
        // A hand-edited or corrupt value is cleaned up instead of failing.
        assertEquals(listOf("tor"), VoiceWords.decode(" Tor ,, "))
    }

    @Test
    fun `all custom words of a check, for the vocabulary lookup`() {
        val custom = CustomWords(blue = listOf("himmel"), red = listOf("feuer"), point = listOf("tor", "treffer"))
        assertEquals(listOf("himmel", "feuer", "tor", "treffer"), custom.all)
        assertFalse(custom.isEmpty)
        assertTrue(CustomWords().isEmpty)
    }

    @Test
    fun `the field text shown for editing`() {
        assertEquals("himmel, azur", VoiceWords.display(listOf("himmel", "azur")))
        assertEquals("", VoiceWords.display(emptyList()))
    }

    // --- no point word (3.4.1) ---------------------------------------------------------------

    @Test
    fun `an emptied point field turns the point word off`() {
        assertEquals(WordsCheck.Ok(CustomWords(pointOff = true)), VoiceWords.check(Language.DE, fields(point = " ")))
        assertFalse(CustomWords(pointOff = true).isEmpty)
    }

    @Test
    fun `the default words typed into the point field are the default`() {
        assertEquals(WordsCheck.Ok(CustomWords()), VoiceWords.check(Language.DE, fields(point = "Punkt, punk")))
        assertEquals(WordsCheck.Ok(CustomWords()), VoiceWords.check(Language.EN, fields(point = "points,point")))
    }

    @Test
    fun `without a point word only the team words and undo are listened for`() {
        val words = CommandWords.of(Language.DE, CustomWords(pointOff = true))
        assertEquals(emptyList<String>(), words.point)
        assertEquals(listOf("blau"), words.blue)
        assertEquals(listOf("rot"), words.red)
        assertEquals(listOf("punkt", "punk"), words.replaced)
        assertEquals(emptySet<String>(), words.alike)
    }

    @Test
    fun `the point field shows its default words and is empty only when off`() {
        assertEquals("punkt, punk", VoiceWords.fieldText(Language.DE, CustomWords(), WordField.POINT))
        assertEquals("treffer", VoiceWords.fieldText(Language.DE, CustomWords(point = listOf("treffer")), WordField.POINT))
        assertEquals("", VoiceWords.fieldText(Language.DE, CustomWords(pointOff = true), WordField.POINT))
        assertEquals("", VoiceWords.fieldText(Language.DE, CustomWords(), WordField.BLUE))
    }

    @Test
    fun `a default point word may become a team word once the point word is off`() {
        val check = VoiceWords.check(Language.DE, fields(blue = "punkt", point = ""))
        assertEquals(WordsCheck.Ok(CustomWords(blue = listOf("punkt"), pointOff = true)), check)
    }
}
