package ch.beachscore.app.history

import ch.beachscore.app.score.Team
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * History as CSV, one row per set, for Excel with Swiss/German regional settings: semicolons,
 * UTF-8 with BOM, CRLF line ends, fields quoted when they contain `;`, `"` or a line break.
 * Matches oldest first. Winners are `blue` / `red` / `draw`, independent of the app language.
 */
object CsvWriter {

    val COLUMNS = listOf(
        "match_id", "match_start", "set_number",
        "blue_player_1", "blue_player_2", "red_player_1", "red_player_2",
        "blue_points", "red_points", "set_winner", "set_duration_seconds", "match_winner", "point_sequence",
    )

    fun write(matches: List<MatchRecord>, players: Map<Long, Player>, zone: ZoneId): String = buildString {
        append('﻿')
        appendRow(COLUMNS)
        for (match in matches.sortedWith(compareBy<MatchRecord> { it.startedAt }.thenBy { it.id })) {
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(match.startedAt), zone)
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            fun name(team: Team, i: Int) = match.lineup.of(team).getOrNull(i)?.let { players[it]?.name ?: "?" }.orEmpty()
            for (set in match.sets) {
                val duration = if (set.startedAt != null && set.endedAt != null) ((set.endedAt - set.startedAt) / 1000).toString() else ""
                appendRow(
                    listOf(
                        match.id.toString(), start, set.index.toString(),
                        name(Team.BLUE, 0), name(Team.BLUE, 1), name(Team.RED, 0), name(Team.RED, 1),
                        set.bluePoints.toString(), set.redPoints.toString(), winner(set.winner), duration,
                        winner(match.winner), set.sequence,
                    ),
                )
            }
        }
    }

    private fun StringBuilder.appendRow(fields: List<String>) {
        fields.joinTo(this, ";") { field(it) }
        append("\r\n")
    }

    fun field(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun winner(team: Team?) = when (team) {
        Team.BLUE -> "blue"
        Team.RED -> "red"
        null -> "draw"
    }
}
