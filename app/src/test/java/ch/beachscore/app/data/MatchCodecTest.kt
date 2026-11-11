package ch.beachscore.app.data

import ch.beachscore.app.score.MatchEvent
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.ScoreEngine
import ch.beachscore.app.score.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MatchCodecTest {

    @Test
    fun `round trip keeps score, target and undo history`() {
        var state = MatchState.new(target = 21)
        repeat(21) { state = ScoreEngine.apply(state, MatchEvent.PointFor(Team.RED)) }
        state = ScoreEngine.apply(state, MatchEvent.NextSet)
        state = ScoreEngine.apply(state, MatchEvent.PointFor(Team.BLUE))

        val decoded = MatchCodec.decode(MatchCodec.encode(state))!!

        assertEquals(state.score, decoded.score)
        assertEquals(state.target, decoded.target)
        assertEquals(state.history, decoded.history)
        // Undo still steps back across the set win after a restore.
        val once = ScoreEngine.apply(decoded, MatchEvent.Undo)
        assertEquals(1, once.score.redSets)
        val twice = ScoreEngine.apply(once, MatchEvent.Undo)
        assertEquals(0, twice.score.redSets)
        assertEquals(20, twice.score.redPoints)
    }

    @Test
    fun `fresh match round trips`() {
        val state = MatchState.new()
        assertEquals(state, MatchCodec.decode(MatchCodec.encode(state)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "garbage",
            "beachscore-match-v1\n15",
            "beachscore-match-v1\nx\n0,0,0,0,-,-",
            "beachscore-match-v1\n15\n0,0,0,0,X,-",
            "beachscore-match-v1\n15\n-1,0,0,0,-,-",
            "beachscore-match-v1\n0\n0,0,0,0,-,-",
            "beachscore-match-v99\n15\n0,0,0,0,-,-",
        ],
    )
    fun `corrupt input decodes to null`(text: String) {
        assertNull(MatchCodec.decode(text))
    }
}
