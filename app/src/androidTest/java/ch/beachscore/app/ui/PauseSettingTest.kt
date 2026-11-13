package ch.beachscore.app.ui

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.fakes.FakeVoice
import ch.beachscore.app.score.Language
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3.3.1: "Pause between words" under the voice settings, stored and passed to the voice input. */
@RunWith(AndroidJUnit4::class)
class PauseSettingTest {

    @get:Rule
    val rule = createComposeRule()

    private val voice = FakeVoice()

    private fun show(settings: FakeSettingsStore) {
        val vm = ScoreboardViewModel(FakeMatchStore(), settings, FakeSpeaker(), clock = SystemClock::elapsedRealtime, voice = voice)
        rule.setContent {
            BeachScoreTheme {
                val state by vm.uiState.collectAsState()
                ScoreboardRoute(vm, state)
            }
        }
        rule.waitUntil(5_000) { vm.uiState.value.loaded }
        rule.runOnIdle {
            vm.onMicPermission(true)
            vm.onForegroundChanged(true)
        }
    }

    @Test
    fun choosingThreeSecondsStoresItAndRestartsListening() {
        val settings = FakeSettingsStore(Settings(voiceCommands = true))
        show(settings)
        rule.waitUntil(5_000) { voice.maxPauseMs == 2000L }
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithText("Pause between words", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("pause_2").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("pause_3").performClick()
        rule.waitUntil(5_000) { voice.maxPauseMs == 3000L }
        assertEquals(3000L, runBlocking { settings.settings.first() }.voicePauseMs)
        rule.onNodeWithTag("pause_3").assertIsSelected()
    }

    @Test
    fun germanLabels() {
        show(FakeSettingsStore(Settings(voiceCommands = true, language = Language.DE, voicePauseMs = 1000L)))
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithText("Pause zwischen den Wörtern", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("pause_1").performScrollTo().assertIsSelected()
    }
}
