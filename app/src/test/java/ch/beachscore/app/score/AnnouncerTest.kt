package ch.beachscore.app.score

import ch.beachscore.app.score.Language.DE
import ch.beachscore.app.score.Language.EN
import ch.beachscore.app.score.MatchEvent.NewMatch
import ch.beachscore.app.score.MatchEvent.NextSet
import ch.beachscore.app.score.MatchEvent.PointFor
import ch.beachscore.app.score.MatchEvent.SetTarget
import ch.beachscore.app.score.MatchEvent.Undo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AnnouncerTest {

    private fun state(blue: Int, red: Int, lastScorer: Team? = null) =
        MatchState(score = Score(bluePoints = blue, redPoints = red, lastScorer = lastScorer))

    private fun phrase(before: MatchState, event: MatchEvent, language: Language): String? =
        Announcer.phrase(before, ScoreEngine.apply(before, event), language)

    @Test
    fun `scoring team's points come first`() {
        // Blue leads 5:2 and Red scores: Red serves next, so "three, five".
        assertEquals("three, five", phrase(state(5, 2), PointFor(Team.RED), EN))
        assertEquals("six, two", phrase(state(5, 2), PointFor(Team.BLUE), EN))
    }

    @Test
    fun `german score uses german number words`() {
        assertEquals("drei, fünf", phrase(state(5, 2), PointFor(Team.RED), DE))
        assertEquals("einundzwanzig, zwanzig", phrase(MatchState(target = 21, score = Score(20, 20)), PointFor(Team.BLUE), DE))
    }

    @Test
    fun `zero is spoken as a word`() {
        assertEquals("one, zero", phrase(state(0, 0), PointFor(Team.BLUE), EN))
        assertEquals("eins, null", phrase(state(0, 0), PointFor(Team.RED), DE))
    }

    @Test
    fun `set win names the winner and their points first`() {
        assertEquals("Set Blue, fifteen, twelve", phrase(state(14, 12), PointFor(Team.BLUE), EN))
        assertEquals("Satz Rot, fünfzehn, zwölf", phrase(state(12, 14), PointFor(Team.RED), DE))
    }

    @Test
    fun `undo announces the restored score as a correction`() {
        // Blue scored to 4:3, then Red scored to 4:4; undoing Red's point restores 4:3 with Blue
        // as last scorer, so Blue's points come first.
        val before = state(3, 3, lastScorer = Team.RED)
            .let { ScoreEngine.apply(it, PointFor(Team.BLUE)) }
            .let { ScoreEngine.apply(it, PointFor(Team.RED)) }
        assertEquals("Correction, four, three", phrase(before, Undo, EN))
        assertEquals("Korrektur, vier, drei", phrase(before, Undo, DE))
    }

    @Test
    fun `undo of a set win announces the running set`() {
        val won = ScoreEngine.apply(state(14, 12, lastScorer = Team.RED), PointFor(Team.BLUE))
        assertEquals("Correction, twelve, fourteen", phrase(won, Undo, EN))
    }

    @Test
    fun `undo back to zero zero`() {
        val one = ScoreEngine.apply(MatchState.new(), PointFor(Team.RED))
        assertEquals("Correction, zero, zero", phrase(one, Undo, EN))
        assertEquals("Korrektur, null, null", phrase(one, Undo, DE))
    }

    @Test
    fun `next set, new match, target change and no-ops are silent`() {
        val won = ScoreEngine.apply(state(14, 1), PointFor(Team.BLUE))
        assertNull(phrase(won, NextSet, EN))
        assertNull(phrase(state(3, 4), NewMatch, EN))
        assertNull(phrase(state(3, 4), SetTarget(21), EN))
        assertNull(phrase(MatchState.new(), Undo, EN), "nothing to undo")
        assertNull(phrase(won, PointFor(Team.RED), EN), "point ignored while set finished")
    }

    @ParameterizedTest
    @CsvSource(
        "0, zero, null", "1, one, eins", "7, seven, sieben", "11, eleven, elf", "12, twelve, zwölf",
        "13, thirteen, dreizehn", "16, sixteen, sechzehn", "17, seventeen, siebzehn",
        "20, twenty, zwanzig", "21, twenty-one, einundzwanzig", "26, twenty-six, sechsundzwanzig",
        "30, thirty, dreißig", "37, thirty-seven, siebenunddreißig", "40, forty, vierzig",
        "61, sixty-one, einundsechzig", "70, seventy, siebzig", "99, ninety-nine, neunundneunzig",
    )
    fun `number words`(n: Int, english: String, german: String) {
        assertEquals(english, NumberWords.of(n, EN))
        assertEquals(german, NumberWords.of(n, DE))
    }

    @Test
    fun `large numbers fall back to digits`() {
        assertEquals("100", NumberWords.of(100, EN))
    }
}
