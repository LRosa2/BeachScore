package ch.beachscore.app.score

/**
 * All scoring rules, as a pure reducer. No Android imports.
 *
 * Events that change nothing return the very same instance, so callers can use identity to
 * skip persisting and announcing.
 */
object ScoreEngine {

    /** Lead needed to win a set; there is no point cap. */
    const val WIN_BY = 2

    fun apply(state: MatchState, event: MatchEvent): MatchState = when (event) {
        is MatchEvent.PointFor -> point(state, event.team)
        MatchEvent.Undo -> undo(state)
        MatchEvent.NextSet -> nextSet(state)
        MatchEvent.NewMatch -> MatchState(target = state.target, lastChange = MatchChange.NEW_MATCH)
        is MatchEvent.SetTarget -> setTarget(state, event.target)
    }

    private fun point(state: MatchState, team: Team): MatchState {
        if (state.setFinished) return state
        var score = state.score.withPoint(team)
        if (wins(score, team, state.target)) score = score.withSetWonBy(team)
        return state.copy(score = score, history = state.history + state.score, lastChange = MatchChange.POINT)
    }

    /**
     * Only the team that just scored can win the set. That matters after the target was lowered
     * mid-set: a point for the trailing team never hands the set to the leader.
     */
    private fun wins(score: Score, team: Team, target: Int): Boolean {
        val own = score.points(team)
        return own >= target && own - score.points(team.other) >= WIN_BY
    }

    private fun undo(state: MatchState): MatchState {
        if (!state.canUndo) return state
        return state.copy(
            score = state.history.last(),
            history = state.history.dropLast(1),
            lastChange = MatchChange.UNDO,
        )
    }

    private fun nextSet(state: MatchState): MatchState {
        if (!state.setFinished) return state
        val score = state.score.copy(bluePoints = 0, redPoints = 0, setWinner = null, lastScorer = null)
        // History is kept, so an undo right after "next set" takes the winning point back.
        return state.copy(score = score, lastChange = MatchChange.NEXT_SET)
    }

    private fun setTarget(state: MatchState, target: Int): MatchState {
        require(target > 0) { "target must be positive: $target" }
        if (target == state.target) return state
        return state.copy(target = target, lastChange = MatchChange.TARGET)
    }
}
