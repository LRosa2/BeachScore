package ch.beachscore.app.score

import ch.beachscore.app.score.MatchEvent.NewMatch
import ch.beachscore.app.score.MatchEvent.NextSet
import ch.beachscore.app.score.MatchEvent.PointFor
import ch.beachscore.app.score.MatchEvent.SetTarget
import ch.beachscore.app.score.MatchEvent.Undo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Phase 3: what the running match records for history (sets, sequences, times). */
class ScoreEngineRecordsTest {

    private var clock = 1_000L

    private fun MatchState.point(team: Team, at: Long = clock.also { clock += 1000 }) =
        ScoreEngine.apply(this, PointFor(team, at))

    private fun MatchState.apply(event: MatchEvent) = ScoreEngine.apply(this, event)

    /** Plays [sequence] ("BBR…") with one second between points. */
    private fun MatchState.play(sequence: String): MatchState =
        sequence.fold(this) { s, c -> s.point(if (c == 'B') Team.BLUE else Team.RED) }

    @Test
    fun `points build the sequence and the start times`() {
        val s = MatchState.new().play("BRB")
        assertEquals("BRB", s.currentSequence)
        assertEquals(1_000L, s.matchStartedAt)
        assertEquals(1_000L, s.currentSetStartedAt)
        assertTrue(s.finishedSets.isEmpty())
    }

    @Test
    fun `a won set is recorded at once with score, target, times and sequence`() {
        val s = MatchState.new().play("B".repeat(15))
        val set = s.finishedSets.single()
        assertEquals(FinishedSet(1, 15, 0, 15, startedAt = 1_000, endedAt = 15_000, sequence = "B".repeat(15)), set)
        assertEquals(Team.BLUE, set.winner)
    }

    @Test
    fun `next set starts a fresh sequence and the second set gets index 2`() {
        var s = MatchState.new().play("R".repeat(15)).apply(NextSet)
        assertEquals("", s.currentSequence)
        assertNull(s.currentSetStartedAt)
        s = s.play("B".repeat(15))
        assertEquals(listOf(1, 2), s.finishedSets.map { it.index })
        assertEquals(16_000L, s.finishedSets[1].startedAt)
        assertEquals(1_000L, s.matchStartedAt)
    }

    @Test
    fun `target at the time of each set is recorded`() {
        val s = MatchState.new().play("B".repeat(15)).apply(NextSet).apply(SetTarget(21)).play("R".repeat(21))
        assertEquals(listOf(15, 21), s.finishedSets.map { it.target })
    }

    @Test
    fun `undo of a normal point shortens the sequence`() {
        val s = MatchState.new().play("BRB").apply(Undo)
        assertEquals("BR", s.currentSequence)
        assertEquals(1_000L, s.currentSetStartedAt)
    }

    @Test
    fun `undo back to the very start clears the start times`() {
        val s = MatchState.new().play("B").apply(Undo)
        assertEquals("", s.currentSequence)
        assertNull(s.currentSetStartedAt)
        assertNull(s.matchStartedAt)
    }

    @Test
    fun `undo of the set-winning point removes the finished set`() {
        val s = MatchState.new().play("B".repeat(15)).apply(Undo)
        assertTrue(s.finishedSets.isEmpty())
        assertEquals("B".repeat(14), s.currentSequence)
        assertEquals(1_000L, s.currentSetStartedAt)
    }

    @Test
    fun `undo right after next set reopens the previous set`() {
        val won = MatchState.new().play("R".repeat(14) + "BR")
        val s = won.apply(NextSet).apply(Undo)
        assertTrue(s.finishedSets.isEmpty())
        assertEquals("R".repeat(14) + "B", s.currentSequence)
        assertEquals(1_000L, s.currentSetStartedAt)
        assertEquals(14, s.score.redPoints)
        // and replaying the point gives the same set again
        val again = s.point(Team.RED, at = won.finishedSets.single().endedAt!!)
        assertEquals(won.finishedSets, again.finishedSets)
    }

    @Test
    fun `undo in the second set keeps the first set`() {
        val s = MatchState.new().play("B".repeat(15)).apply(NextSet).play("RR").apply(Undo).apply(Undo)
        assertEquals(1, s.finishedSets.size)
        assertEquals("", s.currentSequence)
        assertNull(s.currentSetStartedAt)
        assertEquals(1_000L, s.matchStartedAt, "match start survives as long as a set is recorded")
    }

    @Test
    fun `new match clears all records`() {
        val s = MatchState.new().play("B".repeat(15)).apply(NextSet).play("R").apply(NewMatch)
        assertTrue(s.finishedSets.isEmpty())
        assertEquals("", s.currentSequence)
        assertNull(s.matchStartedAt)
        assertNull(s.currentSetStartedAt)
    }
}
