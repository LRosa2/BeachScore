package ch.beachscore.app.fakes

import ch.beachscore.app.data.AddPlayerResult
import ch.beachscore.app.data.HistoryStore
import ch.beachscore.app.data.MatchStore
import ch.beachscore.app.data.PlayerStore
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.MatchRecord
import ch.beachscore.app.history.NameCheck
import ch.beachscore.app.history.Player
import ch.beachscore.app.history.PlayerNames
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.data.SettingsStore
import ch.beachscore.app.score.Language
import ch.beachscore.app.speech.SpeechAvailability
import ch.beachscore.app.speech.Speaker
import ch.beachscore.app.voice.Heard
import ch.beachscore.app.voice.InputDevice
import ch.beachscore.app.voice.MicChoice
import ch.beachscore.app.voice.Verdict
import ch.beachscore.app.voice.CommandWords
import ch.beachscore.app.voice.VoiceControl
import ch.beachscore.app.voice.VoiceSensitivity
import ch.beachscore.app.voice.VoiceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeMatchStore(var saved: PersistedMatch? = null) : MatchStore {
    var saveCount = 0
        private set

    override suspend fun load(): PersistedMatch? = saved

    override suspend fun save(match: PersistedMatch) {
        saved = match
        saveCount++
    }
}

class FakeSettingsStore(initial: Settings = Settings()) : SettingsStore {
    override val settings = MutableStateFlow(initial)

    override suspend fun update(transform: (Settings) -> Settings) {
        settings.update { transform(it).sanitized() }
    }
}

class FakeSpeaker(available: SpeechAvailability = SpeechAvailability.AVAILABLE) : Speaker {
    val spoken = mutableListOf<Pair<String, Language>>()
    var prepared: Language? = null
        private set

    override val availability: StateFlow<SpeechAvailability> = MutableStateFlow(available)

    override fun prepare(language: Language) {
        prepared = language
    }

    override fun speak(text: String, language: Language) {
        spoken += text to language
    }

    val lastText: String? get() = spoken.lastOrNull()?.first
}

class FakeVoice : VoiceControl {
    override val heard = MutableSharedFlow<Heard>(extraBufferCapacity = 16)
    override val status = MutableStateFlow<VoiceStatus>(VoiceStatus.Off)

    /** Last start() arguments while listening, null while stopped. */
    var listening: Pair<Language, VoiceSensitivity>? = null
        private set
    var startCount = 0
        private set

    override val microphones = MutableStateFlow<List<InputDevice>>(emptyList())
    val active = MutableStateFlow<InputDevice?>(null)
    override val activeMicrophone: StateFlow<InputDevice?> = active

    /** Microphone choice of the last start() (null: automatic). */
    var microphone: MicChoice? = null
        private set

    /** Words of the last start() (kept after stop()). */
    var words: CommandWords? = null
        private set

    /** Pause between words of the last start(). */
    var maxPauseMs: Long? = null
        private set

    override fun start(language: Language, sensitivity: VoiceSensitivity, microphone: MicChoice?, words: CommandWords, maxPauseMs: Long) {
        if (listening == language to sensitivity && this.microphone == microphone && this.words == words && this.maxPauseMs == maxPauseMs) return
        if (listening != language to sensitivity || this.words != words || this.maxPauseMs != maxPauseMs) startCount++
        listening = language to sensitivity
        this.microphone = microphone
        this.words = words
        this.maxPauseMs = maxPauseMs
        status.value = VoiceStatus.Listening
    }

    /** Words the "model" knows; null: the model cannot be loaded. */
    var vocabulary: Set<String>? = emptySet()

    /** Every word list passed to [unknownWords]. */
    val checked = mutableListOf<List<String>>()

    override suspend fun unknownWords(language: Language, words: List<String>): List<String>? {
        checked += words
        val known = vocabulary ?: return null
        return words.filter { it !in known }
    }

    override fun stop() {
        listening = null
        status.value = VoiceStatus.Off
    }

    fun hear(text: String, verdict: Verdict, minConfidence: Double? = 1.0) {
        check(heard.tryEmit(Heard(text, minConfidence, verdict)))
    }
}

class FakeHistoryStore : HistoryStore {
    val records = MutableStateFlow<List<MatchRecord>>(emptyList())
    override val matches: Flow<List<MatchRecord>> = records.map { list -> list.sortedByDescending { it.startedAt } }
    private var nextId = 1L

    override suspend fun add(record: MatchRecord): Long {
        val id = nextId++
        records.update { it + record.copy(id = id) }
        return id
    }

    override suspend fun assignLineup(ids: List<Long>, lineup: Lineup) {
        records.update { list -> list.map { if (it.id in ids) it.copy(lineup = lineup) else it } }
    }

    override suspend fun delete(ids: List<Long>) {
        records.update { list -> list.filterNot { it.id in ids } }
    }
}

class FakePlayerStore(vararg names: String) : PlayerStore {
    val all = MutableStateFlow(names.mapIndexed { i, n -> Player(i + 1L, n, archived = false) })
    override val players: Flow<List<Player>> = all.map { list -> list.sortedBy { it.name.lowercase() } }

    override suspend fun add(rawName: String): AddPlayerResult {
        val list = all.value
        return when (val check = PlayerNames.check(rawName, list)) {
            is NameCheck.Ok -> {
                PlayerNames.archivedWithName(check.name, list)?.let { a ->
                    all.update { l -> l.map { if (it.id == a.id) it.copy(archived = false) else it } }
                    return AddPlayerResult.Revived(a.copy(archived = false))
                }
                val p = Player((list.maxOfOrNull { it.id } ?: 0) + 1, check.name, false)
                all.update { it + p }
                AddPlayerResult.Added(p)
            }
            else -> AddPlayerResult.Rejected(check)
        }
    }

    override suspend fun rename(id: Long, rawName: String): NameCheck {
        val check = PlayerNames.check(rawName, all.value, renaming = id)
        if (check is NameCheck.Ok) all.update { l -> l.map { if (it.id == id) it.copy(name = check.name) else it } }
        return check
    }

    override suspend fun archive(id: Long) {
        all.update { l -> l.map { if (it.id == id) it.copy(archived = true) else it } }
    }
}
