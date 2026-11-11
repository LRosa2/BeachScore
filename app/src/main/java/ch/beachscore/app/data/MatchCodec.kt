package ch.beachscore.app.data

import ch.beachscore.app.score.FinishedSet
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.score.Team

/**
 * Plain-text encoding of a running [MatchState]. [decode] returns null for anything it cannot
 * validate, so a corrupt file means "start a fresh match", never a crash.
 *
 * v2 (phase 3) is one `key=value` per line: target, score, the times and sequence of the
 * running set, one `set=` line per finished set and one `hist=` line per undo snapshot. Unknown
 * keys are ignored.
 *
 * v1 (phases 1–2) had target, score and the undo snapshots only. Because there is one snapshot
 * per point, replaying them rebuilds the finished sets and point sequences exactly; only the
 * times are unknown and stay null.
 */
object MatchCodec {

    private const val HEADER_V1 = "beachscore-match-v1"
    private const val HEADER_V2 = "beachscore-match-v2"

    fun encode(state: MatchState): String = buildString {
        append(HEADER_V2)
        line("target", state.target.toString())
        line("score", encodeScore(state.score))
        line("matchStart", time(state.matchStartedAt))
        line("setStart", time(state.currentSetStartedAt))
        line("seq", state.currentSequence)
        state.finishedSets.forEach {
            line("set", listOf(it.index, it.bluePoints, it.redPoints, it.target, time(it.startedAt), time(it.endedAt), it.sequence).joinToString(","))
        }
        state.history.forEach { line("hist", encodeScore(it)) }
    }

    private fun StringBuilder.line(key: String, value: String) {
        append('\n').append(key).append('=').append(value)
    }

    fun decode(text: String): MatchState? = runCatching {
        val lines = text.split('\n')
        when (lines.firstOrNull()) {
            HEADER_V2 -> decodeV2(lines.drop(1))
            HEADER_V1 -> decodeV1(lines.drop(1))
            else -> null
        }
    }.getOrNull()

    private fun decodeV2(lines: List<String>): MatchState? {
        var target: Int? = null
        var score: Score? = null
        var matchStart: Long? = null
        var setStart: Long? = null
        var sequence = ""
        val sets = mutableListOf<FinishedSet>()
        val history = mutableListOf<Score>()
        for (line in lines) {
            val key = line.substringBefore('=', "")
            val value = line.substringAfter('=', "")
            when (key) {
                "target" -> target = value.toInt()
                "score" -> score = decodeScore(value) ?: return null
                "matchStart" -> matchStart = time(value)
                "setStart" -> setStart = time(value)
                "seq" -> sequence = value.also { if (!isSequence(it)) return null }
                "set" -> sets += decodeSet(value) ?: return null
                "hist" -> history += decodeScore(value) ?: return null
            }
        }
        if (target == null || target <= 0 || score == null) return null
        return MatchState(
            score = score,
            target = target,
            history = history,
            finishedSets = sets,
            currentSequence = sequence,
            currentSetStartedAt = setStart,
            matchStartedAt = matchStart,
        )
    }

    private fun decodeV1(lines: List<String>): MatchState? {
        if (lines.size < 2) return null
        val target = lines[0].toInt()
        if (target <= 0) return null
        val score = decodeScore(lines[1]) ?: return null
        val history = lines.drop(2).map { decodeScore(it) ?: return null }
        val base = MatchState(score = score, target = target, history = history)
        return rebuildRecords(base) ?: base
    }

    /**
     * Rebuilds finished sets and the running set's sequence from the undo snapshots of a v1
     * match. Returns null if the snapshots are not one point apart (then the match keeps its
     * score but has no set records).
     */
    private fun rebuildRecords(state: MatchState): MatchState? {
        val sets = mutableListOf<FinishedSet>()
        var sequence = StringBuilder()
        for ((i, before) in state.history.withIndex()) {
            val after = state.history.getOrNull(i + 1) ?: state.score
            val scorer = scorerBetween(before, after) ?: return null
            sequence.append(if (scorer == Team.BLUE) 'B' else 'R')
            if (after.sets(scorer) == before.sets(scorer) + 1) {
                val blue = before.bluePoints + if (scorer == Team.BLUE) 1 else 0
                val red = before.redPoints + if (scorer == Team.RED) 1 else 0
                sets += FinishedSet(sets.size + 1, blue, red, state.target, null, null, sequence.toString())
                // Still on the set-won overlay: the sequence stays; after "next set" it restarts.
                if (after.setWinner == null) sequence = StringBuilder()
            }
        }
        return state.copy(finishedSets = sets, currentSequence = sequence.toString())
    }

    private fun scorerBetween(before: Score, after: Score): Team? {
        val sameSets = after.blueSets == before.blueSets && after.redSets == before.redSets
        return when {
            after.bluePoints == before.bluePoints + 1 && after.redPoints == before.redPoints &&
                (sameSets || after.blueSets == before.blueSets + 1) -> Team.BLUE
            after.redPoints == before.redPoints + 1 && after.bluePoints == before.bluePoints &&
                (sameSets || after.redSets == before.redSets + 1) -> Team.RED
            // "Next set" happened after the point: points are back to 0:0, the set count tells.
            after.bluePoints == 0 && after.redPoints == 0 && after.blueSets == before.blueSets + 1 && after.redSets == before.redSets -> Team.BLUE
            after.bluePoints == 0 && after.redPoints == 0 && after.redSets == before.redSets + 1 && after.blueSets == before.blueSets -> Team.RED
            else -> null
        }
    }

    private fun decodeSet(value: String): FinishedSet? {
        val p = value.split(',')
        if (p.size != 7) return null
        val numbers = p.take(4).map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        if (!isSequence(p[6])) return null
        return FinishedSet(numbers[0], numbers[1], numbers[2], numbers[3], time(p[4]), time(p[5]), p[6])
    }

    private fun isSequence(s: String) = s.all { it == 'B' || it == 'R' }

    private fun time(t: Long?): String = t?.toString() ?: "-"
    private fun time(s: String): Long? = if (s == "-") null else s.toLong()

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
