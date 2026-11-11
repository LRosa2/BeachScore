package ch.beachscore.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.beachscore.app.score.Language
import ch.beachscore.app.ui.BeachScoreTheme
import ch.beachscore.app.ui.BeachScoreRoot
import ch.beachscore.app.ui.Screen
import ch.beachscore.app.ui.ScoreboardViewModel
import ch.beachscore.app.ui.records.RecordsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val container get() = (application as BeachScoreApp).container
    private val viewModel: ScoreboardViewModel by viewModels { container.viewModelFactory }
    private val records: RecordsViewModel by viewModels { container.viewModelFactory }

    /** Hardware keys and voice act only while the scoreboard itself is shown. */
    private var scoreboardShown = true

    /** UI language this activity instance was created with; a change recreates it. */
    private lateinit var uiLanguage: Language

    override fun attachBaseContext(newBase: Context) {
        // The in-app language setting overrides the device locale for resources. Reading one
        // tiny preferences file synchronously here is the price for having the right strings
        // from the first frame on every API level from 26.
        val app = newBase.applicationContext as BeachScoreApp
        uiLanguage = runBlocking { app.container.settingsRepository.settings.first().language }
        super.attachBaseContext(newBase)
        // A delta that only overrides the locale: screen size, orientation etc. keep following
        // the device. Must be applied before anything touches the activity's resources.
        applyOverrideConfiguration(Configuration().apply { setLocale(Locale.forLanguageTag(uiLanguage.tag)) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Volume keys (when not scoring) and announcements both use the media stream.
        volumeControlStream = AudioManager.STREAM_MUSIC
        hideSystemBars()

        setContent {
            BeachScoreTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.loaded, state.settings.language) {
                    if (state.loaded && state.settings.language != uiLanguage) recreate()
                }
                BeachScoreRoot(viewModel, state, records) { screen ->
                    scoreboardShown = screen == Screen.SCOREBOARD
                    viewModel.onScoreboardVisible(scoreboardShown)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Voice listens only while the scoreboard is visible (no background listening).
        viewModel.onMicPermission(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
        viewModel.onForegroundChanged(true)
    }

    override fun onStop() {
        viewModel.onForegroundChanged(false)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (scoreboardShown && container.hardwareKeys.onKey(event.keyCode, event.action, event.repeatCount)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Immersive: bars stay hidden; a swipe from the edge shows them briefly. */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
