package ch.beachscore.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.beachscore.app.score.Language
import ch.beachscore.app.voice.VoiceSensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Settings persistence. [defaultLanguage] applies until the user picks one (spec: the device
 * language if it is German, otherwise English).
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val defaultLanguage: Language,
) : SettingsStore {

    override val settings: Flow<Settings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it.toSettings() }

    override suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            val new = transform(prefs.toSettings()).sanitized()
            prefs[POINTS_PER_SET] = new.pointsPerSet
            prefs[ANNOUNCE] = new.announce
            prefs[LANGUAGE] = new.language.tag
            prefs[TAP_LOCK_MS] = new.tapLockMs
            prefs[HARDWARE_BUTTONS] = new.hardwareButtons
            prefs[VOICE_COMMANDS] = new.voiceCommands
            prefs[VOICE_SENSITIVITY] = new.voiceSensitivity.name
            prefs[SHOW_HEARD] = new.showHeard
        }
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings(language = defaultLanguage)
        return Settings(
            pointsPerSet = this[POINTS_PER_SET] ?: defaults.pointsPerSet,
            announce = this[ANNOUNCE] ?: defaults.announce,
            language = Language.fromTag(this[LANGUAGE]) ?: defaults.language,
            tapLockMs = this[TAP_LOCK_MS] ?: defaults.tapLockMs,
            hardwareButtons = this[HARDWARE_BUTTONS] ?: defaults.hardwareButtons,
            voiceCommands = this[VOICE_COMMANDS] ?: defaults.voiceCommands,
            voiceSensitivity = VoiceSensitivity.entries.firstOrNull { it.name == this[VOICE_SENSITIVITY] }
                ?: defaults.voiceSensitivity,
            showHeard = this[SHOW_HEARD] ?: defaults.showHeard,
        ).sanitized()
    }

    private companion object {
        val POINTS_PER_SET = intPreferencesKey("points_per_set")
        val ANNOUNCE = booleanPreferencesKey("announce")
        val LANGUAGE = stringPreferencesKey("language")
        val TAP_LOCK_MS = longPreferencesKey("tap_lock_ms")
        val HARDWARE_BUTTONS = booleanPreferencesKey("hardware_buttons")
        val VOICE_COMMANDS = booleanPreferencesKey("voice_commands")
        val VOICE_SENSITIVITY = stringPreferencesKey("voice_sensitivity")
        val SHOW_HEARD = booleanPreferencesKey("show_heard")
    }
}
