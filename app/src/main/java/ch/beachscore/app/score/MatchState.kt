package ch.beachscore.app.score

/** The two teams. Names are fixed in phase 1; they are identified by colour. */
enum class Team {
    BLUE, RED;

    val other: Team get() = if (this == BLUE) RED else BLUE
}

/**
 * Everything one undo step restores: points and sets of both teams, who won the set (if it is
 * finished) and who scored last (that team serves next and is announced first).
 */
data class Score(
    val bluePoints: Int = 0,
    val redPoints: Int = 0,
    val blueSets: Int = 0,
    val redSets: Int = 0,
    val setWinner: Team? = null,
    val lastScorer: Team? = null,
) {
    fun points(team: Team): Int = if (team == Team.BLUE) bluePoints else redPoints
    fun sets(team: Team): Int = if (team == Team.BLUE) blueSets else redSets

    internal fun withPoint(team: Team): Score =
        if (team == Team.BLUE) copy(bluePoints = bluePoints + 1, lastScorer = team)
        else copy(redPoints = redPoints + 1, lastScorer = team)

    internal fun withSetWonBy(team: Team): Score =
        if (team == Team.BLUE) copy(blueSets = blueSets + 1, setWinner = team)
        else copy(redSets = redSets + 1, setWinner = team)
}

/** What the most recent event did; lets the announcer and UI react without diffing states. */
enum class MatchChange { NONE, POINT, UNDO, NEXT_SET, NEW_MATCH, TARGET }

/**
 * A set as history records it, added to [MatchState.finishedSets] the moment it is won.
 * [sequence] is the scoring order as `B`/`R` characters. Times are wall-clock milliseconds; null
 * only for sets rebuilt from a match saved before phase 3 (times were not recorded then).
 */
data class FinishedSet(
    val index: Int,
    val bluePoints: Int,
    val redPoints: Int,
    val target: Int,
    val startedAt: Long?,
    val endedAt: Long?,
    val sequence: String,
) {
    val winner: Team get() = if (bluePoints > redPoints) Team.BLUE else Team.RED

    fun points(team: Team): Int = if (team == Team.BLUE) bluePoints else redPoints
}

/**
 * Immutable match state.
 *
 * [history] holds the [Score] before every accepted point of the current match, newest last,
 * so undo can step back across set wins and "next set". It is bounded by the match: a new
 * match clears it.
 *
 * For match history (phase 3) it also records [finishedSets], the running set's
 * [currentSequence] and start time, and when the match started (its first point).
 */
data class MatchState(
    val score: Score = Score(),
    val target: Int = DEFAULT_TARGET,
    val history: List<Score> = emptyList(),
    val lastChange: MatchChange = MatchChange.NONE,
    val finishedSets: List<FinishedSet> = emptyList(),
    val currentSequence: String = "",
    val currentSetStartedAt: Long? = null,
    val matchStartedAt: Long? = null,
) {
    val setFinished: Boolean get() = score.setWinner != null
    val canUndo: Boolean get() = history.isNotEmpty()

    companion object {
        const val DEFAULT_TARGET = 15
        val ALLOWED_TARGETS = listOf(15, 21)

        fun new(target: Int = DEFAULT_TARGET) = MatchState(target = target)
    }
}

sealed interface MatchEvent {
    /** A point; [at] is wall-clock time in ms, recorded for history. */
    data class PointFor(val team: Team, val at: Long = 0L) : MatchEvent
    data object Undo : MatchEvent
    data object NextSet : MatchEvent
    data object NewMatch : MatchEvent
    data class SetTarget(val target: Int) : MatchEvent
}
