package ch.beachscore.app.history

import ch.beachscore.app.score.FinishedSet
import ch.beachscore.app.score.MatchEvent
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.ScoreEngine
import ch.beachscore.app.score.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MatchCloserTest {

    private var t = 10_000L
    private val lineup = Lineup(blue = listOf(1, 2), red = listOf(3))

    private fun MatchState.play(sequence: String): MatchState = sequence.fold(this) { s, c ->
        ScoreEngine.apply(s, MatchEvent.PointFor(if (c == 'B') Team.BLUE else Team.RED, t.also { t += 1000 }))
    }

    @Test
    fun `match without a finished set creates no history entry`() {
        assertNull(MatchCloser.close(MatchState.new(), lineup, now = 99_000))
        assertNull(MatchCloser.close(MatchState.new().play("BBRRB"), lineup, now = 99_000))
    }

    @Test
    fun `set just won on the overlay is included`() {
        val record = MatchCloser.close(MatchState.new().play("B".repeat(15)), lineup, now = 99_000)!!
        assertEquals(1, record.sets.size)
        assertEquals(10_000L, record.startedAt)
        assertEquals(24_000L, record.endedAt, "end = end of the last finished set, not the closing time")
        assertEquals(lineup, record.lineup)
        assertEquals(Team.BLUE, record.winner)
    }

    @Test
    fun `unfinished trailing set is dropped`() {
        val state = MatchState.new().play("R".repeat(15)).let { ScoreEngine.apply(it, MatchEvent.NextSet) }.play("BBBB")
        val record = MatchCloser.close(state, lineup, now = 99_000)!!
        assertEquals(listOf(0 to 15), record.sets.map { it.bluePoints to it.redPoints })
        assertEquals(Team.RED, record.winner)
    }

    @Test
    fun `equal sets is a draw`() {
        val state = MatchState.new().play("B".repeat(15)).let { ScoreEngine.apply(it, MatchEvent.NextSet) }.play("R".repeat(15))
        val record = MatchCloser.close(state, Lineup(), now = 99_000)!!
        assertEquals(1, record.setsWon(Team.BLUE))
        assertEquals(1, record.setsWon(Team.RED))
        assertNull(record.winner)
    }

    @Test
    fun `migrated sets without times fall back to the closing time`() {
        val state = MatchState(finishedSets = listOf(FinishedSet(1, 15, 3, 15, null, null, "")))
        val record = MatchCloser.close(state, Lineup(), now = 77_000)!!
        assertEquals(77_000L, record.startedAt)
        assertEquals(77_000L, record.endedAt)
    }
}
