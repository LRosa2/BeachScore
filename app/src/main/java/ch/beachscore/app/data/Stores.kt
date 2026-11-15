package ch.beachscore.app.data

import ch.beachscore.app.history.Lineup
import ch.beachscore.app.input.RemoteKeys
import ch.beachscore.app.input.TapLock
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.voice.CommandWords
import ch.beachscore.app.voice.CustomWords
import ch.beachscore.app.voice.HalfJoiner
import ch.beachscore.app.voice.MicChoice
import ch.beachscore.app.voice.VoiceSensitivity
import kotlinx.coroutines.flow.Flow

data class Settings(
    val pointsPerSet: Int = MatchState.DEFAULT_TARGET,
    val announce: Boolean = true,
    val language: Language = Language.EN,
    val tapLockMs: Long = TapLock.DEFAULT_MS,
    /** "Remote buttons" (3.4.0): learned keys for Blue point, Red point and Undo. */
    val remoteKeys: RemoteKeys = RemoteKeys(),
    /**
     * The 3.3 toggle "Hardware buttons score points" was on and has not been handed over to
     * [remoteKeys] yet (the view model does that once it knows the sides; see [RemoteKeys.fromVolumeKeys]).
     */
    val legacyVolumeKeys: Boolean = false,
    /** Phase 2: listen for spoken commands. Off by default; needs RECORD_AUDIO. */
    val voiceCommands: Boolean = false,
    val voiceSensitivity: VoiceSensitivity = VoiceSensitivity.NORMAL,
    /** Diagnostics line with the last heard phrase (only while voice commands are on). */
    val showHeard: Boolean = true,
    /** Preferred input for voice commands; null is "Automatic" (Android's routing). */
    val microphone: MicChoice? = null,
    /** The user's voice words per language; a missing language or empty field means the defaults. */
    val voiceWords: Map<Language, CustomWords> = emptyMap(),
    /** "Pause between words": longest pause between the two words of a point command (3.3.1). */
    val voicePauseMs: Long = HalfJoiner.DEFAULT_PAUSE_MS,
) {
    /** The words the recogniser listens for in the current language. */
    fun commandWords(): CommandWords = CommandWords.of(language, voiceWords[language] ?: CustomWords())

    /** Brings values read from disk back into the ranges the UI offers. */
    fun sanitized(): Settings = copy(
        pointsPerSet = pointsPerSet.takeIf { it in MatchState.ALLOWED_TARGETS } ?: MatchState.DEFAULT_TARGET,
        tapLockMs = (tapLockMs.coerceIn(TapLock.MIN_MS, TapLock.MAX_MS) / TapLock.STEP_MS) * TapLock.STEP_MS,
        voicePauseMs = voicePauseMs.takeIf { it in HalfJoiner.PAUSE_CHOICES_MS } ?: HalfJoiner.DEFAULT_PAUSE_MS,
    )
}

/** Everything about the running match that must survive process death. */
data class PersistedMatch(
    val state: MatchState,
    /** Swap state: true when Blue is shown on the left (the initial layout). */
    val blueOnLeft: Boolean = true,
    /** Players per team; kept across matches until changed. */
    val lineup: Lineup = Lineup(),
)

interface MatchStore {
    /** The saved match, or null when there is none or it is unreadable. */
    suspend fun load(): PersistedMatch?
    suspend fun save(match: PersistedMatch)
}

interface SettingsStore {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
