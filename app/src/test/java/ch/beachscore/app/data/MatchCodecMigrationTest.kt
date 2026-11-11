package ch.beachscore.app.data

import ch.beachscore.app.score.FinishedSet
import ch.beachscore.app.score.MatchEvent
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.score.ScoreEngine
import ch.beachscore.app.score.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Phase 3: format v2 and the upgrade of a running match saved by phases 1/2 (v1). */
class MatchCodecMigrationTest {

    private var t = 1_700_000_000_000L

    private fun MatchState.play(sequence: String): MatchState = sequence.fold(this) { s, c ->
        ScoreEngine.apply(s, MatchEvent.PointFor(if (c == 'B') Team.BLUE else Team.RED, t.also { t += 1000 }))
    }

    private fun MatchState.apply(e: MatchEvent) = ScoreEngine.apply(this, e)

    /** Exactly what phases 1 and 2 wrote (MatchCodec v1). */
    private fun encodeV1(state: MatchState): String {
        fun team(x: Team?) = when (x) { Team.BLUE -> "B"; Team.RED -> "R"; null -> "-" }
        fun score(s: Score) = "${s.bluePoints},${s.redPoints},${s.blueSets},${s.redSets},${team(s.setWinner)},${team(s.lastScorer)}"
        return (listOf("beachscore-match-v1", state.target.toString(), score(state.score)) + state.history.map(::score)).joinToString("\n")
    }

    /** What the migration can know: everything except times. */
    private fun MatchState.withoutTimes() = copy(
        finishedSets = finishedSets.map { it.copy(startedAt = null, endedAt = null) },
        currentSetStartedAt = null,
        matchStartedAt = null,
        lastChange = ch.beachscore.app.score.MatchChange.NONE,
    )

    @Test
    fun `v2 round trip keeps every field`() {
        val state = MatchState.new().play("B".repeat(14) + "RB").apply(MatchEvent.NextSet).play("RRB")
        val decoded = MatchCodec.decode(MatchCodec.encode(state))!!
        assertEquals(state.copy(lastChange = ch.beachscore.app.score.MatchChange.NONE), decoded)
    }

    @Test
    fun `v2 round trip with a set just won and with an empty match`() {
        val won = MatchState.new(target = 21).play("R".repeat(21))
        assertEquals(won.copy(lastChange = ch.beachscore.app.score.MatchChange.NONE), MatchCodec.decode(MatchCodec.encode(won)))
        assertEquals(MatchState.new(), MatchCodec.decode(MatchCodec.encode(MatchState.new())))
    }

    @Test
    fun `v1 match in the middle of the second set is rebuilt with its finished set`() {
        // The case on the user's phone: a set finished, "next set", and play continues.
        val original = MatchState.new().play("BRBRBBBBRBBBBBBBBRB").apply(MatchEvent.NextSet).play("RRBR")
        val migrated = MatchCodec.decode(encodeV1(original))!!
        assertEquals(original.withoutTimes(), migrated)
        assertEquals(FinishedSet(1, 15, 4, 15, null, null, "BRBRBBBBRBBBBBBBBRB"), migrated.finishedSets.single())
        assertEquals("RRBR", migrated.currentSequence)
    }

    @Test
    fun `v1 match showing the set-won overlay keeps the set for New match`() {
        val original = MatchState.new().play("R".repeat(13) + "BBR" + "R")
        assertTrue(original.setFinished)
        val migrated = MatchCodec.decode(encodeV1(original))!!
        assertEquals(original.withoutTimes(), migrated)
        assertEquals(1, migrated.finishedSets.size)
    }

    @Test
    fun `v1 match right after next set, undo reopens the rebuilt set`() {
        val original = MatchState.new().play("B".repeat(15)).apply(MatchEvent.NextSet)
        val migrated = MatchCodec.decode(encodeV1(original))!!
        val undone = ScoreEngine.apply(migrated, MatchEvent.Undo)
        assertTrue(undone.finishedSets.isEmpty())
        assertEquals("B".repeat(14), undone.currentSequence)
    }

    @Test
    fun `v1 two finished sets and a lowered target`() {
        val original = MatchState.new(target = 21).play("B".repeat(21)).apply(MatchEvent.NextSet)
            .apply(MatchEvent.SetTarget(15)).play("R".repeat(15)).apply(MatchEvent.NextSet).play("B")
        val migrated = MatchCodec.decode(encodeV1(original))!!
        assertEquals(listOf(21 to 0, 0 to 15), migrated.finishedSets.map { it.bluePoints to it.redPoints })
        assertEquals(listOf(1, 2), migrated.finishedSets.map { it.index })
        assertEquals("B", migrated.currentSequence)
        assertEquals(1, migrated.score.blueSets)
        assertEquals(1, migrated.score.redSets)
    }

    @Test
    fun `v1 fresh match migrates to an empty one`() {
        val migrated = MatchCodec.decode("beachscore-match-v1\n15\n0,0,0,0,-,-")!!
        assertEquals(MatchState.new(), migrated)
    }

    @Test
    fun `inconsistent v1 history still loads, without set records`() {
        // Two snapshots that are not one point apart: keep the score, drop what cannot be rebuilt.
        val text = "beachscore-match-v1\n15\n9,9,0,0,-,B\n0,0,0,0,-,-"
        val migrated = MatchCodec.decode(text)!!
        assertEquals(9, migrated.score.bluePoints)
        assertTrue(migrated.finishedSets.isEmpty())
        assertEquals("", migrated.currentSequence)
        assertNull(migrated.matchStartedAt)
        // and undo does not crash
        ScoreEngine.apply(migrated, MatchEvent.Undo)
    }
}
