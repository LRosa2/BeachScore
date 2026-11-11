package ch.beachscore.app.fakes

import ch.beachscore.app.data.MatchStore
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.data.SettingsStore
import ch.beachscore.app.score.Language
import ch.beachscore.app.speech.SpeechAvailability
import ch.beachscore.app.speech.Speaker
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
