package ch.beachscore.app.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Players are never deleted, only archived, so old matches keep their names. */
@Entity(tableName = "players", indices = [Index("name_key")])
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Normalised lower-case name for the case-insensitive uniqueness check. */
    @ColumnInfo(name = "name_key") val nameKey: String,
    val archived: Boolean = false,
)

/** A closed match. Lineups as up to two player ids per team (null = empty slot). */
@Entity(tableName = "matches", indices = [Index("started_at")])
data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    @ColumnInfo(name = "blue_1") val blue1: Long?,
    @ColumnInfo(name = "blue_2") val blue2: Long?,
    @ColumnInfo(name = "red_1") val red1: Long?,
    @ColumnInfo(name = "red_2") val red2: Long?,
)

@Entity(
    tableName = "sets",
    foreignKeys = [ForeignKey(entity = MatchEntity::class, parentColumns = ["id"], childColumns = ["match_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("match_id")],
)
data class SetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "match_id") val matchId: Long,
    @ColumnInfo(name = "set_index") val setIndex: Int,
    @ColumnInfo(name = "blue_points") val bluePoints: Int,
    @ColumnInfo(name = "red_points") val redPoints: Int,
    val target: Int,
    /** Null only for sets of a match that was running when phase 3 was installed. */
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    /** Scoring order as B/R characters. */
    val sequence: String,
)

data class MatchWithSets(
    @Embedded val match: MatchEntity,
    @Relation(parentColumn = "id", entityColumn = "match_id") val sets: List<SetEntity>,
)

@Dao
interface PlayerDao {
    @Query("SELECT * FROM players ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players")
    suspend fun allOnce(): List<PlayerEntity>

    @Insert
    suspend fun insert(player: PlayerEntity): Long

    @Query("UPDATE players SET name = :name, name_key = :key WHERE id = :id")
    suspend fun rename(id: Long, name: String, key: String)

    @Query("UPDATE players SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)
}

@Dao
interface MatchDao {
    @Transaction
    @Query("SELECT * FROM matches ORDER BY started_at DESC, id DESC")
    fun all(): Flow<List<MatchWithSets>>

    @Insert
    suspend fun insertMatch(match: MatchEntity): Long

    @Insert
    suspend fun insertSets(sets: List<SetEntity>)

    @Transaction
    suspend fun insert(match: MatchEntity, sets: List<SetEntity>): Long {
        val id = insertMatch(match)
        insertSets(sets.map { it.copy(matchId = id) })
        return id
    }

    @Query("UPDATE matches SET blue_1 = :blue1, blue_2 = :blue2, red_1 = :red1, red_2 = :red2 WHERE id IN (:ids)")
    suspend fun assignLineup(ids: List<Long>, blue1: Long?, blue2: Long?, red1: Long?, red2: Long?)

    @Query("DELETE FROM matches WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Database(entities = [PlayerEntity::class, MatchEntity::class, SetEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun players(): PlayerDao
    abstract fun matches(): MatchDao

    companion object {
        fun open(context: Context): HistoryDatabase =
            Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "history.db").build()
    }
}
