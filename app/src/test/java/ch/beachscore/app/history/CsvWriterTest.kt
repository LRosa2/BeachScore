package ch.beachscore.app.history

import ch.beachscore.app.score.FinishedSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CsvWriterTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private fun at(iso: String) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    private val players = listOf(
        Player(1, "Anna", false),
        Player(2, "Ben \"the wall\"", false),
        Player(3, "Müller; Carla", true),
    ).associateBy { it.id }

    private val match = MatchRecord(
        id = 7,
        startedAt = at("2026-11-11T14:05:09"),
        endedAt = at("2026-11-11T14:40:00"),
        lineup = Lineup(blue = listOf(1, 2), red = listOf(3)),
        sets = listOf(
            FinishedSet(1, 15, 12, 15, at("2026-11-11T14:05:09"), at("2026-11-11T14:20:39"), "BRB"),
            FinishedSet(2, 13, 15, 15, null, null, "RRB"),
        ),
    )

    private val header = "match_id;match_start;set_number;blue_player_1;blue_player_2;red_player_1;red_player_2;" +
        "blue_points;red_points;set_winner;set_duration_seconds;match_winner;point_sequence"

    @Test
    fun `bom, header, one row per set, semicolons and crlf`() {
        val csv = CsvWriter.write(listOf(match), players, zone)
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals(header, lines[0])
        assertEquals("7;2026-11-11T14:05:09;1;Anna;\"Ben \"\"the wall\"\"\";\"Müller; Carla\";;15;12;blue;930;draw;BRB", lines[1])
        assertEquals("7;2026-11-11T14:05:09;2;Anna;\"Ben \"\"the wall\"\"\";\"Müller; Carla\";;13;15;red;;draw;RRB", lines[2])
        assertEquals("", lines[3], "file ends with CRLF")
        assertEquals(4, lines.size)
        assertTrue('\n' !in csv.replace("\r\n", ""), "no bare LF")
    }

    @Test
    fun `matches are written oldest first and the match winner is named`() {
        val older = match.copy(id = 3, startedAt = at("2026-11-10T10:00:00"), sets = listOf(FinishedSet(1, 15, 3, 15, null, null, "")), lineup = Lineup())
        val csv = CsvWriter.write(listOf(match, older), players, zone)
        val rows = csv.removePrefix("﻿").split("\r\n").drop(1).filter { it.isNotEmpty() }
        assertEquals(listOf("3", "7", "7"), rows.map { it.substringBefore(';') })
        assertEquals("3;2026-11-10T10:00:00;1;;;;;15;3;blue;;blue;", rows[0])
    }

    @Test
    fun `empty history is just the header`() {
        assertEquals("﻿$header\r\n", CsvWriter.write(emptyList(), players, zone))
    }

    @Test
    fun `fields with line breaks are quoted`() {
        assertEquals("\"a\nb\"", CsvWriter.field("a\nb"))
        assertEquals("plain", CsvWriter.field("plain"))
    }
}
