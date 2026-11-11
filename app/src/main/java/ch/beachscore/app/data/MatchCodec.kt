package ch.beachscore.app.data

import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.score.Team

/**
 * Plain-text encoding of a [MatchState] (score, target and the whole undo history), one
 * [Score] per line. [decode] returns null for anything it cannot fully validate, so a corrupt
 * file means "start a fresh match", never a crash.
 */
object MatchCodec {

    private const val HEADER = "beachscore-match-v1"

    fun encode(state: MatchState): String = buildString {
        append(HEADER).append('\n')
        append(state.target).append('\n')
        append(encodeScore(state.score))
        state.history.forEach { append('\n').append(encodeScore(it)) }
    }

    fun decode(text: String): MatchState? = runCatching {
        val lines = text.split('\n')
        if (lines.size < 3 || lines[0] != HEADER) return null
        val target = lines[1].toInt()
        if (target <= 0) return null
        val score = decodeScore(lines[2]) ?: return null
        val history = lines.drop(3).map { decodeScore(it) ?: return null }
        MatchState(score = score, target = target, history = history)
    }.getOrNull()

    private fun encodeScore(s: Score): String =
        listOf(s.bluePoints, s.redPoints, s.blueSets, s.redSets).joinToString(",") +
            ",${team(s.setWinner)},${team(s.lastScorer)}"

    private fun decodeScore(line: String): Score? {
        val parts = line.split(',')
        if (parts.size != 6) return null
        val numbers = parts.take(4).map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        val winner = team(parts[4]) ?: return null
        val last = team(parts[5]) ?: return null
        return Score(numbers[0], numbers[1], numbers[2], numbers[3], winner.value, last.value)
    }

    private fun team(team: Team?): String = when (team) {
        Team.BLUE -> "B"
        Team.RED -> "R"
        null -> "-"
    }

    /** Wrapper so "no team" can be told apart from "invalid". */
    private class Parsed(val value: Team?)

    private fun team(code: String): Parsed? = when (code) {
        "B" -> Parsed(Team.BLUE)
        "R" -> Parsed(Team.RED)
        "-" -> Parsed(null)
        else -> null
    }
}
