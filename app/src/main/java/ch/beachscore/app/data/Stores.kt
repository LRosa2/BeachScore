package ch.beachscore.app.data

import ch.beachscore.app.input.TapLock
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchState
import kotlinx.coroutines.flow.Flow

data class Settings(
    val pointsPerSet: Int = MatchState.DEFAULT_TARGET,
    val announce: Boolean = true,
    val language: Language = Language.EN,
    val tapLockMs: Long = TapLock.DEFAULT_MS,
    val hardwareButtons: Boolean = false,
) {
    /** Brings values read from disk back into the ranges the UI offers. */
    fun sanitized(): Settings = copy(
        pointsPerSet = pointsPerSet.takeIf { it in MatchState.ALLOWED_TARGETS } ?: MatchState.DEFAULT_TARGET,
        tapLockMs = (tapLockMs.coerceIn(TapLock.MIN_MS, TapLock.MAX_MS) / TapLock.STEP_MS) * TapLock.STEP_MS,
    )
}

/** Everything about the running match that must survive process death. */
data class PersistedMatch(
    val state: MatchState,
    /** Swap state: true when Blue is shown on the left (the initial layout). */
    val blueOnLeft: Boolean = true,
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
