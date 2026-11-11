package ch.beachscore.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.beachscore.app.data.db.HistoryDatabase
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.MatchRecord
import ch.beachscore.app.history.NameCheck
import ch.beachscore.app.score.FinishedSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, HistoryDatabase::class.java,
    ).build()
    private val history = HistoryRepository(db)
    private val players = PlayerRepository(db)

    @After
    fun close() = db.close()

    private fun record(start: Long, lineup: Lineup = Lineup()) = MatchRecord(
        startedAt = start, endedAt = start + 1000, lineup = lineup,
        sets = listOf(FinishedSet(1, 15, 12, 15, start, start + 900, "BRB"), FinishedSet(2, 10, 15, 21, null, null, "")),
    )

    @Test
    fun matchesRoundTripNewestFirst() = runBlocking {
        val a = history.add(record(1_000, Lineup(listOf(1, 2), listOf(3))))
        val b = history.add(record(5_000))
        val all = history.matches.first()
        assertEquals(listOf(b, a), all.map { it.id })
        assertEquals(record(1_000, Lineup(listOf(1, 2), listOf(3))).copy(id = a), all[1])
    }

    @Test
    fun assignAndDeleteSeveralMatches() = runBlocking {
        val ids = (1..3).map { history.add(record(it * 1000L)) }
        history.assignLineup(ids.take(2), Lineup(listOf(7), listOf(8, 9)))
        val byId = history.matches.first().associateBy { it.id }
        assertEquals(Lineup(listOf(7), listOf(8, 9)), byId.getValue(ids[0]).lineup)
        assertEquals(Lineup(listOf(7), listOf(8, 9)), byId.getValue(ids[1]).lineup)
        assertEquals(Lineup(), byId.getValue(ids[2]).lineup)

        history.delete(ids.take(2))
        assertEquals(listOf(ids[2]), history.matches.first().map { it.id })
        val orphanSets = db.query("SELECT COUNT(*) FROM sets WHERE match_id IN (${ids[0]}, ${ids[1]})", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals("sets are deleted with their match", 0, orphanSets)
    }

    @Test
    fun playersAreUniqueRenamedArchivedAndRevived() = runBlocking {
        val anna = (players.add("  Anna ") as AddPlayerResult.Added).player
        assertEquals("Anna", anna.name)
        assertTrue(players.add("ANNA") is AddPlayerResult.Rejected)
        val ben = (players.add("Ben") as AddPlayerResult.Added).player
        assertTrue(players.rename(ben.id, "anna") is NameCheck.Duplicate)
        assertEquals(NameCheck.Ok("Benjamin"), players.rename(ben.id, " Benjamin"))

        players.archive(anna.id)
        assertTrue(players.players.first().single { it.id == anna.id }.archived)
        val revived = players.add("anna")
        assertEquals(AddPlayerResult.Revived(anna.copy(archived = false)), revived)
        assertEquals(listOf("Anna", "Benjamin"), players.players.first().map { it.name })
    }
}
