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
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchEvent
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.ScoreEngine
import ch.beachscore.app.score.Team
import ch.beachscore.app.speech.Speaker
import ch.beachscore.app.voice.CommandParser
import ch.beachscore.app.voice.Heard
import ch.beachscore.app.voice.RejectReason
import ch.beachscore.app.voice.Verdict
import ch.beachscore.app.voice.VoiceControl
import ch.beachscore.app.voice.VoiceWords
import ch.beachscore.app.voice.WordField
import ch.beachscore.app.voice.WordProblem
import ch.beachscore.app.voice.WordsCheck
import ch.beachscore.app.data.AddPlayerResult
import ch.beachscore.app.data.HistoryStore
import ch.beachscore.app.data.PlayerStore
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.MatchCloser
import ch.beachscore.app.history.Player
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
    private val voice: VoiceControl? = null,
    extraInputs: List<ScoreInput> = emptyList(),
    private val history: HistoryStore? = null,
    private val players: PlayerStore? = null,
    /** Wall-clock ms for history times (the tap lock uses the monotonic [clock]). */
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val touch = TouchInput()
    private val tapLock = TapLock(clock)

    private var match: PersistedMatch? = null
    private var settings = Settings()
    private var unlockJob: Job? = null
    private var flashSeq = 0

    private var foreground = false
    private var scoreboardVisible = true
    private var micPermission = false
    private var heardJob: Job? = null
    private var allPlayers: Map<Long, Player> = emptyMap()

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
                    updateListening()
                }
            }
            if (voice != null) launch { voice.heard.collect(::onHeard) }
            if (players != null) launch { players.players.collect(::onPlayers) }
            updateListening()
            launch {
                merge(touch.commands, hardwareKeys.commands, *extraInputs.map { it.commands }.toTypedArray())
                    .collect { handle(it) }
            }
        }
        viewModelScope.launch {
            speaker.availability.collect { availability -> _uiState.update { it.copy(speech = availability) } }
        }
        if (voice != null) {
            viewModelScope.launch {
                voice.status.collect { status -> _uiState.update { it.copy(voice = it.voice.copy(status = status)) } }
            }
            viewModelScope.launch {
                voice.microphones.collect { list -> _uiState.update { it.copy(voice = it.voice.copy(microphones = list)) } }
            }
            viewModelScope.launch {
                voice.activeMicrophone.collect { mic -> _uiState.update { it.copy(voice = it.voice.copy(activeMicrophone = mic)) } }
            }
        }
    }

    override fun onCleared() {
        voice?.stop()
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

    /**
     * Both "New match" buttons: the match is written to history (finished sets only; nothing if
     * none) and a fresh one starts. Lineups stay for the next match.
     */
    fun onNewMatch() {
        val current = match ?: return
        val record = MatchCloser.close(current.state, current.lineup, wallClock())
        if (record != null && history != null) viewModelScope.launch { history.add(record) }
        if (reduce(MatchEvent.NewMatch)) endLock()
    }

    /** Lineup picker tap; see [Lineup.toggle]. Applies to the whole running match. */
    fun togglePlayer(team: Team, playerId: Long) {
        val current = match ?: return
        val lineup = current.lineup.toggle(team, playerId)
        if (lineup == current.lineup) return
        store(current.copy(lineup = lineup))
        publish()
    }

    /** "Add player" in the picker; a new (or revived) player joins [team] if there is room. */
    fun addPlayer(name: String, team: Team?, onResult: (AddPlayerResult) -> Unit) {
        val store = players ?: return
        viewModelScope.launch {
            val result = store.add(name)
            val added = when (result) {
                is AddPlayerResult.Added -> result.player
                is AddPlayerResult.Revived -> result.player
                is AddPlayerResult.Rejected -> null
            }
            if (added != null) {
                allPlayers = allPlayers + (added.id to added)
                if (team != null) togglePlayer(team, added.id)
            }
            onResult(result)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    /** Activity started/stopped: voice listens only while the scoreboard is in the foreground. */
    fun onForegroundChanged(inForeground: Boolean) {
        foreground = inForeground
        updateListening()
    }

    /** History and statistics screens hide the scoreboard; voice pauses meanwhile. */
    fun onScoreboardVisible(visible: Boolean) {
        scoreboardVisible = visible
        updateListening()
    }

    /** Current RECORD_AUDIO grant, checked by the activity whenever it starts. */
    fun onMicPermission(granted: Boolean) {
        micPermission = granted
        if (granted) _uiState.update { it.copy(voicePermissionDenied = false) }
        updateListening()
    }

    /** Answer to the permission request started by switching "Voice commands" on. */
    fun onMicPermissionResult(granted: Boolean) {
        micPermission = granted
        _uiState.update { it.copy(voicePermissionDenied = !granted) }
        if (granted) updateSettings { it.copy(voiceCommands = true) }
        updateListening()
    }

    /**
     * Settings "Save" (and "Reset", with that field empty) of the voice words of [language]:
     * checks the field texts, then the vocabulary of the speech model, and stores them only if
     * all is well. The outcome is [ScoreboardUiState.wordsSave].
     */
    fun saveVoiceWords(language: Language, fields: Map<WordField, String>) {
        val voice = voice ?: return
        viewModelScope.launch {
            val words = when (val check = VoiceWords.check(language, fields)) {
                is WordsCheck.Failed -> return@launch setWordsSave(WordsSave.Failed(check.problem))
                is WordsCheck.Ok -> check.words
            }
            if (!words.isEmpty) {
                setWordsSave(WordsSave.Checking)
                val unknown = voice.unknownWords(language, words.all)
                    ?: return@launch setWordsSave(WordsSave.Failed(WordProblem.ModelUnavailable))
                if (unknown.isNotEmpty()) return@launch setWordsSave(WordsSave.Failed(WordProblem.Unknown(unknown.first())))
            }
            settingsStore.update { current ->
                val others = current.voiceWords - language
                current.copy(voiceWords = if (words.isEmpty) others else others + (language to words))
            }
            setWordsSave(WordsSave.Saved)
        }
    }

    /** A voice-word field was edited: the last save message no longer applies. */
    fun onVoiceWordsEdited() = setWordsSave(WordsSave.Idle)

    private fun setWordsSave(state: WordsSave) = _uiState.update { it.copy(wordsSave = state) }

    /** Settings "Test voice": speaks the current score even when announcements are off. */
    fun onTestVoice() {
        val score = match?.state?.score ?: return
        speaker.speak(Announcer.scoreWords(score, score.lastScorer, settings.language), settings.language)
    }

    // --- internals --------------------------------------------------------------------------

    /** Runs one command from any source; the verdict feeds the voice diagnostics line. */
    private fun handle(command: ScoreCommand): Verdict {
        val current = match ?: return Verdict.Rejected(RejectReason.UNKNOWN)
        return when (command) {
            is ScoreCommand.Point -> point(teamOn(command.side, current), command)
            is ScoreCommand.TeamPoint -> point(command.team, command)
            ScoreCommand.Undo ->
                if (reduce(MatchEvent.Undo)) {
                    endLock()
                    Verdict.Accepted(command)
                } else {
                    Verdict.Rejected(RejectReason.NOTHING_TO_UNDO)
                }
        }
    }

    private fun point(team: Team, command: ScoreCommand): Verdict {
        val current = match ?: return Verdict.Rejected(RejectReason.UNKNOWN)
        // Blocked while the set-won overlay is up; must not start a lock either.
        if (current.state.setFinished) return Verdict.Rejected(RejectReason.SET_WON)
        if (!tapLock.tryAcquire()) return Verdict.Rejected(RejectReason.LOCKED)
        reduce(MatchEvent.PointFor(team, wallClock()))
        startLock(team)
        return Verdict.Accepted(command)
    }

    private fun onHeard(heard: Heard) {
        val verdict = when (val v = heard.verdict) {
            is Verdict.Accepted -> handle(v.command)
            is Verdict.Rejected -> v
        }
        if (!settings.showHeard) return
        // Without pauses, noise right after a command arrives as its own "[unk]" result; it
        // must not replace the command's line before it could be read.
        val shown = _uiState.value.voice.lastHeard
        if (shown?.verdict is Verdict.Accepted && heard.text.split(' ').all { it == CommandParser.UNKNOWN_WORD }) return
        heardJob?.cancel()
        _uiState.update { it.copy(voice = it.voice.copy(lastHeard = heard.copy(verdict = verdict), heardSeq = it.voice.heardSeq + 1)) }
        heardJob = viewModelScope.launch {
            delay(HEARD_VISIBLE_MS)
            _uiState.update { it.copy(voice = it.voice.copy(lastHeard = null)) }
        }
    }

    private fun onPlayers(list: List<Player>) {
        allPlayers = list.associateBy { it.id }
        val current = match
        if (current != null) {
            // Archived players leave the running lineup (they stay in old matches).
            val archived = list.filter { it.archived }.map { it.id }.toSet()
            val lineup = current.lineup.without(archived)
            if (lineup != current.lineup) store(current.copy(lineup = lineup))
        }
        publish()
    }

    private fun updateListening() {
        val voice = voice ?: return
        if (match != null && settings.voiceCommands && foreground && !micPermission) {
            // Revoked in the system settings while voice was on: switch off and explain.
            _uiState.update { it.copy(voicePermissionDenied = true) }
            updateSettings { it.copy(voiceCommands = false) }
        }
        val want = match != null && settings.voiceCommands && foreground && scoreboardVisible && micPermission
        if (want) voice.start(settings.language, settings.voiceSensitivity, settings.microphone, settings.commandWords()) else voice.stop()
        if (!want || !settings.showHeard) {
            heardJob?.cancel()
            _uiState.update { it.copy(voice = it.voice.copy(lastHeard = null)) }
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
        fun teamUi(team: Team) = TeamUi(
            team, score.points(team), score.sets(team),
            players = m.lineup.of(team).map { allPlayers[it] ?: Player(it, "?", archived = false) },
        )
        val leftTeam = teamOn(Side.LEFT, m)
        _uiState.update {
            it.copy(
                loaded = loaded ?: it.loaded,
                left = teamUi(leftTeam),
                right = teamUi(leftTeam.other),
                canUndo = m.state.canUndo,
                lineup = m.lineup,
                activePlayers = allPlayers.values.filterNot { it.archived }.sortedBy { p -> p.name.lowercase() },
                setWon = score.setWinner?.let { w -> SetWonUi(w, score.points(w), score.points(w.other)) },
                settings = settings,
            )
        }
    }

    companion object {
        /** How long the "what was heard" line stays before it fades. */
        const val HEARD_VISIBLE_MS = 4000L
    }
}
