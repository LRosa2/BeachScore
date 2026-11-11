package ch.beachscore.app.data

import ch.beachscore.app.data.db.HistoryDatabase
import ch.beachscore.app.data.db.MatchEntity
import ch.beachscore.app.data.db.MatchWithSets
import ch.beachscore.app.data.db.PlayerEntity
import ch.beachscore.app.data.db.SetEntity
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.MatchRecord
import ch.beachscore.app.history.NameCheck
import ch.beachscore.app.history.Player
import ch.beachscore.app.history.PlayerNames
import ch.beachscore.app.score.FinishedSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface HistoryStore {
    /** Newest first. */
    val matches: Flow<List<MatchRecord>>
    suspend fun add(record: MatchRecord): Long
    suspend fun assignLineup(ids: List<Long>, lineup: Lineup)
    suspend fun delete(ids: List<Long>)
}

sealed interface AddPlayerResult {
    data class Added(val player: Player) : AddPlayerResult

    /** The name belonged to an archived player, who is active again (with their history). */
    data class Revived(val player: Player) : AddPlayerResult
    data class Rejected(val reason: NameCheck) : AddPlayerResult
}

interface PlayerStore {
    /** All players including archived ones, by name. */
    val players: Flow<List<Player>>
    suspend fun add(rawName: String): AddPlayerResult

    /** Returns [NameCheck.Ok] on success, otherwise why the name was refused. */
    suspend fun rename(id: Long, rawName: String): NameCheck
    suspend fun archive(id: Long)
}

class HistoryRepository(private val db: HistoryDatabase) : HistoryStore {

    override val matches: Flow<List<MatchRecord>> = db.matches().all().map { list -> list.map(::toRecord) }

    override suspend fun add(record: MatchRecord): Long = db.matches().insert(
        MatchEntity(
            startedAt = record.startedAt,
            endedAt = record.endedAt,
            blue1 = record.lineup.blue.getOrNull(0),
            blue2 = record.lineup.blue.getOrNull(1),
            red1 = record.lineup.red.getOrNull(0),
            red2 = record.lineup.red.getOrNull(1),
        ),
        record.sets.map {
            SetEntity(
                matchId = 0, setIndex = it.index, bluePoints = it.bluePoints, redPoints = it.redPoints,
                target = it.target, startedAt = it.startedAt, endedAt = it.endedAt, sequence = it.sequence,
            )
        },
    )

    override suspend fun assignLineup(ids: List<Long>, lineup: Lineup) {
        if (ids.isEmpty()) return
        db.matches().assignLineup(ids, lineup.blue.getOrNull(0), lineup.blue.getOrNull(1), lineup.red.getOrNull(0), lineup.red.getOrNull(1))
    }

    override suspend fun delete(ids: List<Long>) {
        if (ids.isNotEmpty()) db.matches().delete(ids)
    }

    private fun toRecord(m: MatchWithSets) = MatchRecord(
        id = m.match.id,
        startedAt = m.match.startedAt,
        endedAt = m.match.endedAt,
        lineup = Lineup(listOfNotNull(m.match.blue1, m.match.blue2), listOfNotNull(m.match.red1, m.match.red2)),
        sets = m.sets.sortedBy { it.setIndex }.map {
            FinishedSet(it.setIndex, it.bluePoints, it.redPoints, it.target, it.startedAt, it.endedAt, it.sequence)
        },
    )
}

class PlayerRepository(private val db: HistoryDatabase) : PlayerStore {

    override val players: Flow<List<Player>> = db.players().all().map { list -> list.map { Player(it.id, it.name, it.archived) } }

    private suspend fun current() = db.players().allOnce().map { Player(it.id, it.name, it.archived) }

    override suspend fun add(rawName: String): AddPlayerResult {
        val all = current()
        return when (val check = PlayerNames.check(rawName, all)) {
            is NameCheck.Ok -> {
                PlayerNames.archivedWithName(check.name, all)?.let { archived ->
                    db.players().setArchived(archived.id, false)
                    return AddPlayerResult.Revived(archived.copy(archived = false))
                }
                val id = db.players().insert(PlayerEntity(name = check.name, nameKey = PlayerNames.key(check.name)))
                AddPlayerResult.Added(Player(id, check.name, archived = false))
            }
            else -> AddPlayerResult.Rejected(check)
        }
    }

    override suspend fun rename(id: Long, rawName: String): NameCheck {
        val check = PlayerNames.check(rawName, current(), renaming = id)
        if (check is NameCheck.Ok) db.players().rename(id, check.name, PlayerNames.key(check.name))
        return check
    }

    override suspend fun archive(id: Long) = db.players().setArchived(id, true)
}
