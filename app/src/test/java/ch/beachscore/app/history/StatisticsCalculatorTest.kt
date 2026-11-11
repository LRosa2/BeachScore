package ch.beachscore.app.history

import ch.beachscore.app.score.FinishedSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StatisticsCalculatorTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val now = at("2026-11-11T10:00:00")

    private val anna = Player(1, "Anna", false)
    private val ben = Player(2, "Ben", false)
    private val carla = Player(3, "Carla", false)
    private val dora = Player(4, "Dora", archived = true)
    private val players = listOf(anna, ben, carla, dora).associateBy { it.id }

    private fun at(iso: String) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    private var nextId = 1L

    /** sets as "15:12" strings, Blue first. */
    private fun match(blue: List<Player>, red: List<Player>, vararg sets: String, start: String = "2026-11-11T09:00:00") =
        MatchRecord(
            id = nextId++,
            startedAt = at(start),
            endedAt = at(start) + 600_000,
            lineup = Lineup(blue.map { it.id }, red.map { it.id }),
            sets = sets.mapIndexed { i, s ->
                val (b, r) = s.split(':').map(String::toInt)
                FinishedSet(i + 1, b, r, 15, null, null, "")
            },
        )

    private fun stats(matches: List<MatchRecord>, period: Period = Period.ALL_TIME) =
        StatisticsCalculator.calculate(matches, players, period, now, zone)

    private fun Statistics.of(p: Player) = this.players.single { it.player == p }.record

    @Test
    fun `wins, losses, sets and points from both sides`() {
        val s = stats(
            listOf(
                match(listOf(anna, ben), listOf(carla, dora), "15:12", "13:15", "15:10"),
                match(listOf(carla), listOf(anna), "15:5"),
            ),
        )
        assertEquals(Record(matches = 2, wins = 1, losses = 1, draws = 0, setsWon = 2, setsLost = 2, pointsFor = 48, pointsAgainst = 52), s.of(anna))
        assertEquals(Record(1, 1, 0, 0, 2, 1, 43, 37), s.of(ben))
        assertEquals(Record(2, 1, 1, 0, 2, 2, 52, 48), s.of(carla))
        assertEquals(0.5, s.of(anna).winRate)
        assertEquals(-4, s.of(anna).pointDifference)
    }

    @Test
    fun `equal sets count as a draw for everyone`() {
        val s = stats(listOf(match(listOf(anna), listOf(ben), "15:10", "8:15")))
        assertEquals(Record(1, 0, 0, 1, 1, 1, 23, 25), s.of(anna))
        assertEquals(Record(1, 0, 0, 1, 1, 1, 25, 23), s.of(ben))
        assertEquals(0.0, s.of(anna).winRate)
    }

    @Test
    fun `archived players keep their statistics and name`() {
        val s = stats(listOf(match(listOf(dora), listOf(anna), "15:3")))
        assertEquals(1, s.of(dora).wins)
        assertTrue(s.players.single { it.player == dora }.player.archived)
    }

    @Test
    fun `one-sided lineup counts for that side, has no head-to-head`() {
        val matches = listOf(match(listOf(anna, ben), emptyList(), "15:12"))
        val s = stats(matches)
        assertEquals(1, s.of(anna).wins)
        assertEquals(1, s.pairs.single().record.wins)
        assertEquals(1, s.counted)
        assertEquals(0, s.excluded)
        assertTrue(StatisticsCalculator.headToHead(matches, setOf(anna.id), players).isEmpty())
    }

    @Test
    fun `matches without players are excluded and counted`() {
        val s = stats(listOf(match(emptyList(), emptyList(), "15:12"), match(listOf(anna), listOf(ben), "15:1")))
        assertEquals(1, s.counted)
        assertEquals(1, s.excluded)
        assertEquals(2, s.players.size)
    }

    @Test
    fun `single-player teams count for the player but form no pair`() {
        val s = stats(listOf(match(listOf(anna), listOf(ben, carla), "15:12")))
        assertEquals(1, s.of(anna).wins)
        assertEquals(1, s.pairs.size)
        assertEquals(setOf(ben, carla), s.pairs.single().players.toSet())
        assertEquals(1, s.pairs.single().record.losses)
    }

    @Test
    fun `pairs are unordered`() {
        val s = stats(
            listOf(
                match(listOf(anna, ben), listOf(carla), "15:12"),
                match(listOf(carla), listOf(ben, anna), "15:12"),
            ),
        )
        val pair = s.pairs.single()
        assertEquals(setOf(anna, ben), pair.players.toSet())
        assertEquals(Record(2, 1, 1, 0, 1, 1, 27, 27), pair.record)
    }

    @Test
    fun `head-to-head of a player lists each opposing player`() {
        val matches = listOf(
            match(listOf(anna, ben), listOf(carla, dora), "15:12"),
            match(listOf(anna), listOf(carla), "10:15"),
        )
        val rows = StatisticsCalculator.headToHead(matches, setOf(anna.id), players)
        assertEquals(Record(2, 1, 1, 0, 1, 1, 25, 27), rows.single { it.opponent == listOf(carla) }.record)
        assertEquals(Record(1, 1, 0, 0, 1, 0, 15, 12), rows.single { it.opponent == listOf(dora) }.record)
        assertEquals(2, rows.size)
    }

    @Test
    fun `head-to-head of a pair lists each opposing team, pair or single`() {
        val matches = listOf(
            match(listOf(anna, ben), listOf(carla, dora), "15:12"),
            match(listOf(dora, carla), listOf(ben, anna), "15:9", "15:9"),
            match(listOf(anna, ben), listOf(carla), "15:0"),
            match(listOf(anna), listOf(carla), "15:0"), // Anna alone: not the pair
        )
        val rows = StatisticsCalculator.headToHead(matches, setOf(anna.id, ben.id), players)
        assertEquals(2, rows.size)
        val vsPair = rows.single { it.opponent.toSet() == setOf(carla, dora) }.record
        assertEquals(Record(2, 1, 1, 0, 1, 2, 33, 42), vsPair)
        assertEquals(Record(1, 1, 0, 0, 1, 0, 15, 0), rows.single { it.opponent == listOf(carla) }.record)
    }

    @Test
    fun `period boundaries use local calendar days`() {
        val matches = listOf(
            match(listOf(anna), listOf(ben), "15:0", start = "2026-11-11T00:00:00"), // today, first second
            match(listOf(anna), listOf(ben), "15:0", start = "2026-11-10T23:59:59"), // yesterday
            match(listOf(anna), listOf(ben), "15:0", start = "2026-10-13T00:00:00"), // 29 days back: in last 30
            match(listOf(anna), listOf(ben), "15:0", start = "2026-10-12T23:59:59"), // 30 days back: out
            match(listOf(anna), listOf(ben), "15:0", start = "2026-01-01T00:00:00"), // this year
            match(listOf(anna), listOf(ben), "15:0", start = "2025-12-31T23:59:59"), // last year
        )
        assertEquals(1, stats(matches, Period.TODAY).of(anna).matches)
        assertEquals(3, stats(matches, Period.LAST_30_DAYS).of(anna).matches)
        assertEquals(5, stats(matches, Period.THIS_YEAR).of(anna).matches)
        assertEquals(6, stats(matches, Period.ALL_TIME).of(anna).matches)
    }

    @Test
    fun `period across the daylight saving change still counts calendar days`() {
        // 2026-10-25 is the switch to winter time in Zurich (a 25-hour day).
        val matches = listOf(match(listOf(anna), listOf(ben), "15:0", start = "2026-10-13T00:30:00"))
        assertEquals(1, stats(matches, Period.LAST_30_DAYS).of(anna).matches)
    }

    @Test
    fun `no matches in the period gives empty tables`() {
        val s = stats(listOf(match(listOf(anna), listOf(ben), "15:0", start = "2025-06-01T12:00:00")), Period.THIS_YEAR)
        assertTrue(s.players.isEmpty())
        assertTrue(s.pairs.isEmpty())
        assertEquals(0, s.counted)
    }

    @Test
    fun `sorting by win rate then matches, or by matches`() {
        val s = stats(
            listOf(
                match(listOf(anna), listOf(ben), "15:0"),
                match(listOf(anna), listOf(carla), "0:15"),
                match(listOf(ben), listOf(dora), "15:0"),
                match(listOf(carla), listOf(dora), "15:0"),
            ),
        )
        // win rates: Anna 1/2, Ben 1/2, Carla 2/2, Dora 0/2 — Ben vs Anna tie on rate and matches → name
        assertEquals(listOf(carla, anna, ben, dora), StatisticsCalculator.sorted(s.players, StatSort.WIN_RATE).map { it.player })
        assertEquals(listOf(carla, anna, ben, dora), StatisticsCalculator.sorted(s.players, StatSort.MATCHES).map { it.player })
    }

    @Test
    fun `win rate is undefined without matches`() {
        assertNull(Record().winRate)
    }
}
