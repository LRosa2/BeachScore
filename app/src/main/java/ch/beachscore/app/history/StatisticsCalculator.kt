package ch.beachscore.app.history

import ch.beachscore.app.score.Team
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Period { TODAY, LAST_30_DAYS, THIS_YEAR, ALL_TIME }

enum class StatSort { WIN_RATE, MATCHES }

/** One side's record over some matches. */
data class Record(
    val matches: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val setsWon: Int = 0,
    val setsLost: Int = 0,
    val pointsFor: Int = 0,
    val pointsAgainst: Int = 0,
) {
    /** wins / matches; null without matches. */
    val winRate: Double? get() = if (matches == 0) null else wins.toDouble() / matches
    val pointDifference: Int get() = pointsFor - pointsAgainst

    operator fun plus(o: Record) = Record(
        matches + o.matches, wins + o.wins, losses + o.losses, draws + o.draws,
        setsWon + o.setsWon, setsLost + o.setsLost, pointsFor + o.pointsFor, pointsAgainst + o.pointsAgainst,
    )
}

data class PlayerRow(val player: Player, val record: Record)

data class PairRow(val players: List<Player>, val record: Record)

/** Head-to-head: the opposing player, or the opposing team (one or two players) for a pair. */
data class HeadToHeadRow(val opponent: List<Player>, val record: Record)

data class Statistics(
    val players: List<PlayerRow>,
    val pairs: List<PairRow>,
    /** Matches in the period that count (at least one player on one side). */
    val counted: Int,
    /** Matches in the period without any player. */
    val excluded: Int,
)

/**
 * All statistics, as pure functions over closed matches. A match counts for every player in
 * its lineup; a two-player team also counts as a pair; head-to-head needs players on both
 * sides. Periods are local calendar days of the match start.
 */
object StatisticsCalculator {

    fun inPeriod(matches: List<MatchRecord>, period: Period, now: Long, zone: ZoneId): List<MatchRecord> {
        if (period == Period.ALL_TIME) return matches
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from: LocalDate = when (period) {
            Period.TODAY -> today
            Period.LAST_30_DAYS -> today.minusDays(29)
            Period.THIS_YEAR -> today.withDayOfYear(1)
            Period.ALL_TIME -> error("handled above")
        }
        val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val untilMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return matches.filter { it.startedAt in fromMillis until untilMillis }
    }

    fun calculate(matches: List<MatchRecord>, players: Map<Long, Player>, period: Period, now: Long, zone: ZoneId): Statistics {
        val inPeriod = inPeriod(matches, period, now, zone)
        val counted = inPeriod.filterNot { it.lineup.isEmpty }
        val perPlayer = mutableMapOf<Long, Record>()
        val perPair = mutableMapOf<Set<Long>, Record>()
        for (match in counted) {
            for (team in Team.entries) {
                val ids = match.lineup.of(team)
                if (ids.isEmpty()) continue
                val record = recordFor(match, team)
                ids.forEach { perPlayer[it] = (perPlayer[it] ?: Record()) + record }
                if (ids.size == 2) ids.toSet().let { perPair[it] = (perPair[it] ?: Record()) + record }
            }
        }
        return Statistics(
            players = sorted(perPlayer.map { (id, r) -> PlayerRow(player(id, players), r) }, StatSort.WIN_RATE),
            pairs = sortedPairs(perPair.map { (ids, r) -> PairRow(ids.map { player(it, players) }.sortedBy { it.name.lowercase() }, r) }, StatSort.WIN_RATE),
            counted = counted.size,
            excluded = inPeriod.size - counted.size,
        )
    }

    /**
     * Record of [subject] (one player, or a pair as a team) against each opponent, over
     * [matches] (already filtered by period). Matches with an empty opposing side are skipped.
     */
    fun headToHead(matches: List<MatchRecord>, subject: Set<Long>, players: Map<Long, Player>): List<HeadToHeadRow> {
        val perOpponent = mutableMapOf<List<Long>, Record>()
        for (match in matches) {
            val side = Team.entries.firstOrNull { team ->
                val ids = match.lineup.of(team).toSet()
                if (subject.size == 1) subject.first() in ids else ids == subject
            } ?: continue
            val opponents = match.lineup.of(side.other)
            if (opponents.isEmpty()) continue
            val record = recordFor(match, side)
            val keys = if (subject.size == 1) opponents.map { listOf(it) } else listOf(opponents.sorted())
            keys.forEach { perOpponent[it] = (perOpponent[it] ?: Record()) + record }
        }
        return perOpponent.map { (ids, r) -> HeadToHeadRow(ids.map { player(it, players) }.sortedBy { it.name.lowercase() }, r) }
            .sortedWith(compareByDescending<HeadToHeadRow> { it.record.matches }.thenBy { it.opponent.joinToString { p -> p.name.lowercase() } })
    }

    fun sorted(rows: List<PlayerRow>, sort: StatSort): List<PlayerRow> =
        rows.sortedWith(comparator(sort, { it.record }, { it.player.name.lowercase() }))

    fun sortedPairs(rows: List<PairRow>, sort: StatSort): List<PairRow> =
        rows.sortedWith(comparator(sort, { it.record }, { it.players.joinToString { p -> p.name.lowercase() } }))

    private fun <T> comparator(sort: StatSort, record: (T) -> Record, name: (T) -> String): Comparator<T> {
        val rate = compareByDescending<T> { record(it).winRate ?: -1.0 }
        val count = compareByDescending<T> { record(it).matches }
        val primary = if (sort == StatSort.WIN_RATE) rate.then(count) else count.then(rate)
        return primary.thenBy(name)
    }

    private fun recordFor(match: MatchRecord, team: Team): Record {
        val won = match.setsWon(team)
        val lost = match.setsWon(team.other)
        return Record(
            matches = 1,
            wins = if (won > lost) 1 else 0,
            losses = if (lost > won) 1 else 0,
            draws = if (won == lost) 1 else 0,
            setsWon = won,
            setsLost = lost,
            pointsFor = match.sets.sumOf { it.points(team) },
            pointsAgainst = match.sets.sumOf { it.points(team.other) },
        )
    }

    private fun player(id: Long, players: Map<Long, Player>) = players[id] ?: Player(id, "?", archived = true)
}
