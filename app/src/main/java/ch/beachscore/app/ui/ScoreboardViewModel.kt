package ch.beachscore.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.beachscore.app.data.MatchStore
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.data.SettingsStore
import ch.beachscore.app.input.HardwareKeyInput
import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.input.ScoreInput
import ch.beachscore.app.input.Side
import ch.beachscore.app.input.TapLock
import ch.beachscore.app.input.TouchInput
import ch.beachscore.app.score.Announcer
import ch.beachscore.app.score.MatchEvent
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.ScoreEngine
import ch.beachscore.app.score.Team
import ch.beachscore.app.speech.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wires every input → [TapLock] → [ScoreEngine] → [MatchStore] → [Speaker], and exposes one
 * [uiState]. All state changes happen on the main thread.
 *
 * Inputs speak in sides; this class maps a side to a team using the swap state, so touch and
 * hardware keys always score for the team that is shown on that side.
 */
class ScoreboardViewModel(
    private val matchStore: MatchStore,
    private val settingsStore: SettingsStore,
    private val speaker: Speaker,
    clock: () -> Long,
    private val hardwareKeys: HardwareKeyInput = HardwareKeyInput(),
    extraInputs: List<ScoreInput> = emptyList(),
) : ViewModel() {

    private val touch = TouchInput()
    private val tapLock = TapLock(clock)

    private var match: PersistedMatch? = null
    private var settings = Settings()
    private var unlockJob: Job? = null
    private var flashSeq = 0

    private val _uiState = MutableStateFlow(ScoreboardUiState())
    val uiState: StateFlow<ScoreboardUiState> = _uiState.asStateFlow()

    /** Latest match to write; a StateFlow so a burst of changes writes only the newest. */
    private val pendingSave = MutableStateFlow<PersistedMatch?>(null)

    init {
        viewModelScope.launch {
            val initialSettings = settingsStore.settings.first()
            val restored = matchStore.load()
            applySettings(initialSettings)
            match = restored ?: PersistedMatch(MatchState.new(initialSettings.pointsPerSet))
            syncTarget()
            publish(loaded = true)

            launch { pendingSave.filterNotNull().collect { matchStore.save(it) } }
            launch {
                settingsStore.settings.drop(1).collect {
                    applySettings(it)
                    syncTarget()
                    publish()
                }
            }
            launch {
                merge(touch.commands, hardwareKeys.commands, *extraInputs.map { it.commands }.toTypedArray())
                    .collect(::handle)
            }
        }
        viewModelScope.launch {
            speaker.availability.collect { availability -> _uiState.update { it.copy(speech = availability) } }
        }
    }

    // --- UI entry points -------------------------------------------------------------------

    fun onTap(side: Side) = touch.point(side)

    fun onUndo() = touch.undo()

    fun onSwapSides() {
        val current = match ?: return
        store(current.copy(blueOnLeft = !current.blueOnLeft))
        publish()
    }

    fun onNextSet() {
        reduce(MatchEvent.NextSet)
    }

    fun onNewMatch() {
        if (reduce(MatchEvent.NewMatch)) endLock()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    /** Settings "Test voice": speaks the current score even when announcements are off. */
    fun onTestVoice() {
        val score = match?.state?.score ?: return
        speaker.speak(Announcer.scoreWords(score, score.lastScorer, settings.language), settings.language)
    }

    // --- internals --------------------------------------------------------------------------

    private fun handle(command: ScoreCommand) {
        val current = match ?: return
        when (command) {
            is ScoreCommand.Point -> {
                // Blocked while the set-won overlay is up; must not start a lock either.
                if (current.state.setFinished) return
                if (!tapLock.tryAcquire()) return
                val team = teamOn(command.side, current)
                reduce(MatchEvent.PointFor(team))
                startLock(team)
            }
            ScoreCommand.Undo -> if (reduce(MatchEvent.Undo)) endLock()
        }
    }

    /** Applies [event]; persists, announces and publishes if it changed anything. */
    private fun reduce(event: MatchEvent): Boolean {
        val current = match ?: return false
        val old = current.state
        val new = ScoreEngine.apply(old, event)
        if (new === old) return false
        store(current.copy(state = new))
        if (settings.announce) {
            Announcer.phrase(old, new, settings.language)?.let { speaker.speak(it, settings.language) }
        }
        publish()
        return true
    }

    private fun store(updated: PersistedMatch) {
        match = updated
        pendingSave.value = updated
    }

    private fun applySettings(new: Settings) {
        settings = new
        tapLock.durationMs = new.tapLockMs
        hardwareKeys.enabled = new.hardwareButtons
        speaker.prepare(new.language)
    }

    /** Keeps the match target equal to the "points per set" setting. Silent. */
    private fun syncTarget() {
        val current = match ?: return
        if (current.state.target == settings.pointsPerSet) return
        store(current.copy(state = ScoreEngine.apply(current.state, MatchEvent.SetTarget(settings.pointsPerSet))))
    }

    private fun startLock(team: Team) {
        flashSeq++
        unlockJob?.cancel()
        _uiState.update { it.copy(locked = true, lastPoint = PointFlash(team, flashSeq)) }
        val duration = tapLock.durationMs
        unlockJob = viewModelScope.launch {
            delay(duration)
            _uiState.update { it.copy(locked = false) }
        }
    }

    /** Undoing the guarded point frees the lock, so the right team can be credited at once. */
    private fun endLock() {
        tapLock.release()
        unlockJob?.cancel()
        _uiState.update { it.copy(locked = false) }
    }

    private fun teamOn(side: Side, m: PersistedMatch): Team {
        val left = if (m.blueOnLeft) Team.BLUE else Team.RED
        return if (side == Side.LEFT) left else left.other
    }

    private fun publish(loaded: Boolean? = null) {
        val m = match ?: return
        val score = m.state.score
        fun teamUi(team: Team) = TeamUi(team, score.points(team), score.sets(team))
        val leftTeam = teamOn(Side.LEFT, m)
        _uiState.update {
            it.copy(
                loaded = loaded ?: it.loaded,
                left = teamUi(leftTeam),
                right = teamUi(leftTeam.other),
                canUndo = m.state.canUndo,
                setWon = score.setWinner?.let { w -> SetWonUi(w, score.points(w), score.points(w.other)) },
                settings = settings,
            )
        }
    }
}
