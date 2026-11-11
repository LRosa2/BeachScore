package ch.beachscore.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.beachscore.app.history.Lineup
import kotlinx.coroutines.flow.first

/**
 * Match persistence in its own DataStore file, separate from settings, so a corrupt match file
 * never costs the settings (spec: "start a fresh match, keep settings if readable").
 */
class MatchRepository(private val dataStore: DataStore<Preferences>) : MatchStore {

    override suspend fun load(): PersistedMatch? {
        val prefs = runCatching { dataStore.data.first() }.getOrNull() ?: return null
        val state = prefs[MATCH]?.let(MatchCodec::decode) ?: return null
        return PersistedMatch(
            state,
            blueOnLeft = prefs[BLUE_ON_LEFT] ?: true,
            lineup = Lineup(ids(prefs[LINEUP_BLUE]), ids(prefs[LINEUP_RED])),
        )
    }

    override suspend fun save(match: PersistedMatch) {
        dataStore.edit {
            it[MATCH] = MatchCodec.encode(match.state)
            it[BLUE_ON_LEFT] = match.blueOnLeft
            it[LINEUP_BLUE] = match.lineup.blue.joinToString(",")
            it[LINEUP_RED] = match.lineup.red.joinToString(",")
        }
    }

    /** Lineup ids; anything unreadable means "no players" (older files have none). */
    private fun ids(text: String?): List<Long> =
        text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.distinct().take(Lineup.MAX_PER_TEAM)

    private companion object {
        val MATCH = stringPreferencesKey("match")
        val BLUE_ON_LEFT = booleanPreferencesKey("blue_on_left")
        val LINEUP_BLUE = stringPreferencesKey("lineup_blue")
        val LINEUP_RED = stringPreferencesKey("lineup_red")
    }
}
