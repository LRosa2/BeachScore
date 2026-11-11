package ch.beachscore.app.score

/**
 * Builds the text to speak after a state change. Pure: no Android imports.
 *
 * Order rule: the team that scored last is named first (it serves next). Without a last scorer
 * (0:0 at the start of a set) Blue comes first.
 */
object Announcer {

    fun phrase(previous: MatchState, new: MatchState, language: Language): String? {
        if (new === previous) return null
        return when (new.lastChange) {
            MatchChange.POINT -> {
                val winner = new.score.setWinner
                if (winner != null) {
                    "${setWord(language)} ${teamName(winner, language)}, ${scoreWords(new.score, winner, language)}"
                } else {
                    scoreWords(new.score, new.score.lastScorer, language)
                }
            }
            MatchChange.UNDO -> "${correctionWord(language)}, ${scoreWords(new.score, new.score.lastScorer, language)}"
            MatchChange.NONE, MatchChange.NEXT_SET, MatchChange.NEW_MATCH, MatchChange.TARGET -> null
        }
    }

    /** "five, three": [first]'s points, then the other team's. */
    fun scoreWords(score: Score, first: Team?, language: Language): String {
        val lead = first ?: Team.BLUE
        return "${NumberWords.of(score.points(lead), language)}, ${NumberWords.of(score.points(lead.other), language)}"
    }

    fun teamName(team: Team, language: Language): String = when (language) {
        Language.EN -> if (team == Team.BLUE) "Blue" else "Red"
        Language.DE -> if (team == Team.BLUE) "Blau" else "Rot"
    }

    private fun setWord(language: Language) = if (language == Language.DE) "Satz" else "Set"
    private fun correctionWord(language: Language) = if (language == Language.DE) "Korrektur" else "Correction"
}
