package ch.beachscore.app.ui

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.fakes.FakeVoice
import ch.beachscore.app.voice.RejectReason
import ch.beachscore.app.voice.Verdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3.5.0: the "Minimum confidence" slider and the heard line's threshold (English labels). */
@RunWith(AndroidJUnit4::class)
class MinConfidenceSettingTest {

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
    fun theSliderShowsStoresAndAppliesTheValue() {
        val settings = FakeSettingsStore(Settings(voiceCommands = true, voiceMinConfidencePct = 70))
        show(settings)
        rule.waitUntil(5_000) { voice.listening?.second == 0.70 }
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("min_confidence_label", useUnmergedTree = true).performScrollTo().assertTextEquals("Minimum confidence: 0.70")
        rule.onNodeWithText("Strict 0.95 · Normal 0.85 · Relaxed 0.70", useUnmergedTree = true).performScrollTo()
        rule.onNodeWithTag("min_confidence").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        rule.waitUntil(5_000) { voice.listening?.second == 0.50 }
        assertEquals(50, runBlocking { settings.settings.first() }.voiceMinConfidencePct)
        rule.onNodeWithTag("min_confidence_label", useUnmergedTree = true).assertTextEquals("Minimum confidence: 0.50")
    }

    @Test
    fun aLowConfidenceRejectionShowsTheThreshold() {
        show(FakeSettingsStore(Settings(voiceCommands = true, voiceMinConfidencePct = 70)))
        rule.waitUntil(5_000) { voice.listening != null }
        rule.runOnIdle { voice.hear("point blue", Verdict.Rejected(RejectReason.LOW_CONFIDENCE), minConfidence = 0.62) }
        rule.onNodeWithText("0.62", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("✗ unsure: below 0.70", useUnmergedTree = true).assertExists()
    }
}
