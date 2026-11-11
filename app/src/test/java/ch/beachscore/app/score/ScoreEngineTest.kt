package ch.beachscore.app.score

import ch.beachscore.app.score.MatchEvent.NewMatch
import ch.beachscore.app.score.MatchEvent.NextSet
import ch.beachscore.app.score.MatchEvent.PointFor
import ch.beachscore.app.score.MatchEvent.SetTarget
import ch.beachscore.app.score.MatchEvent.Undo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScoreEngineTest {

    private fun MatchState.apply(vararg events: MatchEvent): MatchState =
        events.fold(this) { state, event -> ScoreEngine.apply(state, event) }

    /** Plays points so that the set reaches [blue]:[red] without anyone winning on the way. */
    private fun MatchState.playTo(blue: Int, red: Int): MatchState {
        var state = this
        val common = minOf(blue, red)
        repeat(common) { state = state.apply(PointFor(Team.BLUE), PointFor(Team.RED)) }
        repeat(blue - common) { state = state.apply(PointFor(Team.BLUE)) }
        repeat(red - common) { state = state.apply(PointFor(Team.RED)) }
        return state
    }

    @Test
    fun `new match starts at zero with target 15`() {
        val state = MatchState.new()
        assertEquals(0, state.score.points(Team.BLUE))
        assertEquals(0, state.score.points(Team.RED))
        assertEquals(0, state.score.sets(Team.BLUE))
        assertEquals(15, state.target)
        assertNull(state.score.setWinner)
        assertFalse(state.canUndo)
    }

    @Test
    fun `a point adds exactly one to the scoring team`() {
        val state = MatchState.new().apply(PointFor(Team.RED))
        assertEquals(0, state.score.points(Team.BLUE))
        assertEquals(1, state.score.points(Team.RED))
        assertEquals(Team.RED, state.score.lastScorer)
        assertTrue(state.canUndo)
        assertEquals(MatchChange.POINT, state.lastChange)
    }

    @Test
    fun `set is won at 15 with a two point lead`() {
        val state = MatchState.new().playTo(14, 12).apply(PointFor(Team.BLUE))
        assertEquals(Team.BLUE, state.score.setWinner)
        assertEquals(15, state.score.points(Team.BLUE))
        assertEquals(12, state.score.points(Team.RED))
        assertEquals(1, state.score.sets(Team.BLUE))
        assertEquals(0, state.score.sets(Team.RED))
    }

    @Test
    fun `set is won at 21 when the target is 21`() {
        val at15 = MatchState.new(target = 21).playTo(15, 10)
        assertNull(at15.score.setWinner)
        val won = at15.playTo(20, 10).apply(PointFor(Team.BLUE))
        assertEquals(Team.BLUE, won.score.setWinner)
        assertEquals(21, won.score.points(Team.BLUE))
    }

    @Test
    fun `14 to 14 needs a two point lead and has no cap`() {
        var state = MatchState.new().playTo(14, 14)
        state = state.apply(PointFor(Team.BLUE))
        assertNull(state.score.setWinner, "15:14 is not a win")
        state = state.apply(PointFor(Team.RED), PointFor(Team.RED))
        assertNull(state.score.setWinner, "15:16 is not a win")
        state = state.apply(PointFor(Team.BLUE), PointFor(Team.BLUE), PointFor(Team.RED))
        assertNull(state.score.setWinner, "17:17 is not a win")
        state = state.apply(PointFor(Team.RED), PointFor(Team.RED))
        assertEquals(Team.RED, state.score.setWinner)
        assertEquals(17, state.score.points(Team.BLUE))
        assertEquals(19, state.score.points(Team.RED))
    }

    @Test
    fun `20 to 20 needs a two point lead at target 21`() {
        var state = MatchState.new(target = 21).playTo(20, 20)
        state = state.apply(PointFor(Team.RED))
        assertNull(state.score.setWinner)
        state = state.apply(PointFor(Team.RED))
        assertEquals(Team.RED, state.score.setWinner)
        assertEquals(22, state.score.points(Team.RED))
    }

    @Test
    fun `points are ignored while a set is finished`() {
        val won = MatchState.new().playTo(15, 3)
        assertSame(won, ScoreEngine.apply(won, PointFor(Team.RED)))
    }

    @Test
    fun `undo reverts a normal point`() {
        val state = MatchState.new().apply(PointFor(Team.BLUE), PointFor(Team.RED), Undo)
        assertEquals(1, state.score.points(Team.BLUE))
        assertEquals(0, state.score.points(Team.RED))
        assertEquals(Team.BLUE, state.score.lastScorer)
        assertEquals(MatchChange.UNDO, state.lastChange)
    }

    @Test
    fun `undo with nothing to undo changes nothing`() {
        val state = MatchState.new()
        assertSame(state, ScoreEngine.apply(state, Undo))
    }

    @Test
    fun `undo across a set win returns to the running set`() {
        val won = MatchState.new().playTo(14, 12).apply(PointFor(Team.BLUE))
        val undone = won.apply(Undo)
        assertNull(undone.score.setWinner)
        assertEquals(14, undone.score.points(Team.BLUE))
        assertEquals(12, undone.score.points(Team.RED))
        assertEquals(0, undone.score.sets(Team.BLUE))
        // Scoring works again in the running set.
        val continued = undone.apply(PointFor(Team.RED))
        assertEquals(14, continued.score.points(Team.BLUE))
        assertEquals(13, continued.score.points(Team.RED))
        assertNull(continued.score.setWinner)
    }

    @Test
    fun `next set resets points and keeps set counters`() {
        val next = MatchState.new().playTo(15, 9).apply(NextSet)
        assertEquals(0, next.score.points(Team.BLUE))
        assertEquals(0, next.score.points(Team.RED))
        assertEquals(1, next.score.sets(Team.BLUE))
        assertNull(next.score.setWinner)
        assertNull(next.score.lastScorer)
        assertEquals(MatchChange.NEXT_SET, next.lastChange)
    }

    @Test
    fun `next set does nothing while the set is still running`() {
        val running = MatchState.new().playTo(3, 2)
        assertSame(running, ScoreEngine.apply(running, NextSet))
    }

    @Test
    fun `undo after next set takes the winning point back`() {
        val undone = MatchState.new().playTo(15, 9).apply(NextSet, Undo)
        assertEquals(14, undone.score.points(Team.BLUE))
        assertEquals(9, undone.score.points(Team.RED))
        assertEquals(0, undone.score.sets(Team.BLUE))
        assertNull(undone.score.setWinner)
    }

    @Test
    fun `new match resets points sets and history but keeps the target`() {
        val fresh = MatchState.new(target = 21).playTo(21, 5).apply(NextSet).playTo(3, 4).apply(NewMatch)
        assertEquals(Score(), fresh.score)
        assertEquals(21, fresh.target)
        assertFalse(fresh.canUndo)
        assertEquals(MatchChange.NEW_MATCH, fresh.lastChange)
    }

    @Test
    fun `changing the target mid-set applies to the running set`() {
        val at16 = MatchState.new(target = 21).playTo(16, 12)
        val retargeted = at16.apply(SetTarget(15))
        assertEquals(15, retargeted.target)
        assertNull(retargeted.score.setWinner, "changing the target never awards a set by itself")
        assertTrue(retargeted.canUndo, "history is kept")
        assertNull(retargeted.apply(PointFor(Team.RED)).score.setWinner, "only the scoring team can win")
        assertEquals(Team.BLUE, retargeted.apply(PointFor(Team.BLUE)).score.setWinner)
    }

    @Test
    fun `raising the target mid-set lets play continue`() {
        val state = MatchState.new().playTo(13, 10).apply(SetTarget(21), PointFor(Team.BLUE), PointFor(Team.BLUE))
        assertNull(state.score.setWinner, "15:10 is not a win at 21")
    }

    @Test
    fun `setting the same target changes nothing`() {
        val state = MatchState.new()
        assertSame(state, ScoreEngine.apply(state, SetTarget(15)))
    }
}
