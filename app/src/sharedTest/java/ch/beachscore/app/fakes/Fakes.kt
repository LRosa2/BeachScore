package ch.beachscore.app.fakes

import ch.beachscore.app.data.MatchStore
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.data.SettingsStore
import ch.beachscore.app.score.Language
import ch.beachscore.app.speech.SpeechAvailability
import ch.beachscore.app.speech.Speaker
import ch.beachscore.app.voice.Heard
import ch.beachscore.app.voice.Verdict
import ch.beachscore.app.voice.VoiceControl
import ch.beachscore.app.voice.VoiceSensitivity
import ch.beachscore.app.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    override fun start(language: Language, sensitivity: VoiceSensitivity) {
        if (listening == language to sensitivity) return
        listening = language to sensitivity
        startCount++
        status.value = VoiceStatus.Listening
    }

    override fun stop() {
        listening = null
        status.value = VoiceStatus.Off
    }

    fun hear(text: String, verdict: Verdict, minConfidence: Double? = 1.0) {
        check(heard.tryEmit(Heard(text, minConfidence, verdict)))
    }
}
