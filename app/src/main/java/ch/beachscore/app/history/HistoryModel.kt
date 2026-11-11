package ch.beachscore.app.history

import ch.beachscore.app.score.FinishedSet
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Team

data class Player(val id: Long, val name: String, val archived: Boolean)

/** Up to two player ids per team; a player is never on both teams. */
data class Lineup(val blue: List<Long> = emptyList(), val red: List<Long> = emptyList()) {

    fun of(team: Team): List<Long> = if (team == Team.BLUE) blue else red

    val isEmpty: Boolean get() = blue.isEmpty() && red.isEmpty()

    /**
     * Picker tap: removes [id] from [team] if it is there; otherwise moves it to [team] (taking it
     * off the other team). A full team ignores the tap.
     */
    fun toggle(team: Team, id: Long): Lineup {
        val own = of(team)
        if (id in own) return with(team, own - id)
        if (own.size >= MAX_PER_TEAM) return this
        return with(team.other, of(team.other) - id).let { it.with(team, own + id) }
    }

    fun without(ids: Set<Long>) = Lineup(blue.filterNot { it in ids }, red.filterNot { it in ids })

    private fun with(team: Team, ids: List<Long>) = if (team == Team.BLUE) copy(blue = ids) else copy(red = ids)

    companion object {
        const val MAX_PER_TEAM = 2
    }
}

/** A closed match as history stores it. */
data class MatchRecord(
    val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val lineup: Lineup,
    val sets: List<FinishedSet>,
) {
    fun setsWon(team: Team): Int = sets.count { it.winner == team }

    /** The team with more sets; null for a draw. */
    val winner: Team?
        get() = when {
            setsWon(Team.BLUE) > setsWon(Team.RED) -> Team.BLUE
            setsWon(Team.RED) > setsWon(Team.BLUE) -> Team.RED
            else -> null
        }
}

/** Running match → history record, or nothing when no set was finished. */
object MatchCloser {
    fun close(state: MatchState, lineup: Lineup, now: Long): MatchRecord? {
        val sets = state.finishedSets
        if (sets.isEmpty()) return null
        return MatchRecord(
            startedAt = state.matchStartedAt ?: sets.first().startedAt ?: now,
            endedAt = sets.last().endedAt ?: now,
            lineup = lineup,
            sets = sets,
        )
    }
}
