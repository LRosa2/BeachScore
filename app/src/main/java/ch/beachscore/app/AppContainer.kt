package ch.beachscore.app

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.beachscore.app.data.MatchRepository
import ch.beachscore.app.data.SettingsRepository
import ch.beachscore.app.input.HardwareKeyInput
import ch.beachscore.app.score.Language
import ch.beachscore.app.speech.TtsSpeaker
import ch.beachscore.app.ui.ScoreboardViewModel
import ch.beachscore.app.voice.EchoGuard
import ch.beachscore.app.voice.ModelStore
import ch.beachscore.app.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale

// Two files: a corrupt match file is replaced by an empty one (fresh match) and never touches
// the settings file.
private val Context.matchDataStore by preferencesDataStore(
    name = "match",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Process-wide objects. TTS lives here so an activity restart does not re-initialise it. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val matchRepository = MatchRepository(app.matchDataStore)

    // Locale.getDefault() is the device language: the app never changes the process default.
    val settingsRepository = SettingsRepository(app.settingsDataStore, Language.defaultFor(Locale.getDefault().language))

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Shared by TTS (reports when it speaks) and the voice input (ignores itself meanwhile). */
    val echoGuard = EchoGuard(clock = SystemClock::elapsedRealtime)

    val speaker by lazy { TtsSpeaker(app, echoGuard) }

    val hardwareKeys = HardwareKeyInput()

    val voiceInput by lazy { VoiceInput(app, ModelStore(app), echoGuard, appScope) }

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ScoreboardViewModel(
                matchStore = matchRepository,
                settingsStore = settingsRepository,
                speaker = speaker,
                clock = SystemClock::elapsedRealtime,
                hardwareKeys = hardwareKeys,
                voice = voiceInput,
            )
        }
    }
}
