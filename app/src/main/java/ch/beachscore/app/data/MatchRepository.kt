package ch.beachscore.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Match persistence in its own DataStore file, separate from settings, so a corrupt match file
 * never costs the settings (spec: "start a fresh match, keep settings if readable").
 */
class MatchRepository(private val dataStore: DataStore<Preferences>) : MatchStore {

    override suspend fun load(): PersistedMatch? {
        val prefs = runCatching { dataStore.data.first() }.getOrNull() ?: return null
        val state = prefs[MATCH]?.let(MatchCodec::decode) ?: return null
        return PersistedMatch(state, blueOnLeft = prefs[BLUE_ON_LEFT] ?: true)
    }

    override suspend fun save(match: PersistedMatch) {
        dataStore.edit {
            it[MATCH] = MatchCodec.encode(match.state)
            it[BLUE_ON_LEFT] = match.blueOnLeft
        }
    }

    private companion object {
        val MATCH = stringPreferencesKey("match")
        val BLUE_ON_LEFT = booleanPreferencesKey("blue_on_left")
    }
}
