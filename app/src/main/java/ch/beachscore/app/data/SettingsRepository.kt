package ch.beachscore.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.beachscore.app.input.RemoteKeys
import ch.beachscore.app.score.Language
import ch.beachscore.app.voice.CustomWords
import ch.beachscore.app.voice.MicChoice
import ch.beachscore.app.voice.VoiceWords
import ch.beachscore.app.voice.WordField
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
            // Until the hand-over the old toggle stays and no remote buttons are written, so
            // "not yet migrated" survives other changes; afterwards only the new key exists.
            if (new.legacyVolumeKeys) {
                prefs.remove(REMOTE_KEYS)
            } else {
                prefs[REMOTE_KEYS] = new.remoteKeys.encode()
                prefs.remove(HARDWARE_BUTTONS)
            }
            prefs[VOICE_COMMANDS] = new.voiceCommands
            prefs[VOICE_SENSITIVITY] = new.voiceSensitivity.name
            prefs[SHOW_HEARD] = new.showHeard
            prefs[MICROPHONE] = new.microphone?.encode() ?: ""
            prefs[VOICE_PAUSE_MS] = new.voicePauseMs
            for (language in Language.entries) {
                val words = new.voiceWords[language] ?: CustomWords()
                for (field in WordField.entries) {
                    val off = field == WordField.POINT && words.pointOff
                    prefs[wordsKey(language, field)] = if (off) VoiceWords.POINT_OFF else VoiceWords.encode(words[field])
                }
            }
        }
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings(language = defaultLanguage)
        return Settings(
            pointsPerSet = this[POINTS_PER_SET] ?: defaults.pointsPerSet,
            announce = this[ANNOUNCE] ?: defaults.announce,
            language = Language.fromTag(this[LANGUAGE]) ?: defaults.language,
            tapLockMs = this[TAP_LOCK_MS] ?: defaults.tapLockMs,
            remoteKeys = RemoteKeys.decode(this[REMOTE_KEYS]),
            legacyVolumeKeys = this[REMOTE_KEYS] == null && this[HARDWARE_BUTTONS] == true,
            voiceCommands = this[VOICE_COMMANDS] ?: defaults.voiceCommands,
            voiceSensitivity = VoiceSensitivity.entries.firstOrNull { it.name == this[VOICE_SENSITIVITY] }
                ?: defaults.voiceSensitivity,
            showHeard = this[SHOW_HEARD] ?: defaults.showHeard,
            microphone = MicChoice.decode(this[MICROPHONE]),
            voicePauseMs = this[VOICE_PAUSE_MS] ?: defaults.voicePauseMs,
            voiceWords = Language.entries.associateWith { language ->
                WordField.entries.fold(CustomWords()) { words, field ->
                    val raw = this[wordsKey(language, field)]
                    if (field == WordField.POINT && raw == VoiceWords.POINT_OFF) words.copy(pointOff = true)
                    else words.with(field, VoiceWords.decode(raw))
                }
            }.filterValues { !it.isEmpty },
        ).sanitized()
    }

    private companion object {
        val POINTS_PER_SET = intPreferencesKey("points_per_set")
        val ANNOUNCE = booleanPreferencesKey("announce")
        val LANGUAGE = stringPreferencesKey("language")
        val TAP_LOCK_MS = longPreferencesKey("tap_lock_ms")
        /** 3.3 and earlier: volume keys score left/right. Read once for the migration. */
        val HARDWARE_BUTTONS = booleanPreferencesKey("hardware_buttons")
        val REMOTE_KEYS = stringPreferencesKey("remote_keys")
        val VOICE_COMMANDS = booleanPreferencesKey("voice_commands")
        val VOICE_SENSITIVITY = stringPreferencesKey("voice_sensitivity")
        val SHOW_HEARD = booleanPreferencesKey("show_heard")
        val MICROPHONE = stringPreferencesKey("microphone")
        val VOICE_PAUSE_MS = longPreferencesKey("voice_pause_ms")

        /** "voice_words_de_blue", ...: comma separated, empty for the defaults; point "-" for none. */
        fun wordsKey(language: Language, field: WordField) =
            stringPreferencesKey("voice_words_${language.tag}_${field.name.lowercase()}")
    }
}
