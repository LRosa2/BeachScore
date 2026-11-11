package ch.beachscore.app.ui

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.fakes.FakeVoice
import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.score.Team
import ch.beachscore.app.voice.Verdict
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScoreboardSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        matchStore: FakeMatchStore = FakeMatchStore(),
        settings: Settings = Settings(),
        voice: FakeVoice? = null,
    ): ScoreboardViewModel {
        val vm = ScoreboardViewModel(
            matchStore, FakeSettingsStore(settings), FakeSpeaker(), clock = SystemClock::elapsedRealtime, voice = voice,
        )
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
        return vm
    }

    @Test
    fun voiceIsInvisibleWhenOff() {
        val voice = FakeVoice()
        show(voice = voice)
        rule.onNodeWithTag("voice_indicator").assertDoesNotExist()
        assertNull(voice.listening)
    }

    @Test
    fun voicePointShowsHeardLineAndScoresForTheNamedTeam() {
        val voice = FakeVoice()
        show(settings = Settings(voiceCommands = true), voice = voice)
        rule.onNodeWithTag("voice_indicator").assertIsDisplayed()
        rule.runOnIdle { voice.hear("point red", Verdict.Accepted(ScoreCommand.TeamPoint(Team.RED)), 0.97) }
        rule.onNodeWithTag("points_RIGHT").assertTextEquals("1")
        rule.onNodeWithTag("heard_line").assertIsDisplayed()
        rule.onNodeWithText("“point red”").assertIsDisplayed()
        rule.onNodeWithText("0.97").assertIsDisplayed()

        rule.runOnIdle { voice.hear("undo", Verdict.Accepted(ScoreCommand.Undo)) }
        rule.onNodeWithTag("points_RIGHT").assertTextEquals("0")
    }

    @Test
    fun tapLeftIncrementsAndImmediateSecondTapIsIgnored() {
        show()
        rule.onNodeWithTag("points_LEFT").assertTextEquals("0")

        rule.onNodeWithTag("half_LEFT").performClick()
        rule.onNodeWithTag("points_LEFT").assertTextEquals("1")

        // Immediately again, on either half: the tap lock (1.5 s by default) ignores both.
        rule.onNodeWithTag("half_LEFT").performClick()
        rule.onNodeWithTag("half_RIGHT").performClick()
        rule.onNodeWithTag("points_LEFT").assertTextEquals("1")
        rule.onNodeWithTag("points_RIGHT").assertTextEquals("0")
    }

    @Test
    fun undoInTheStripRevertsThePoint() {
        show()
        rule.onNodeWithTag("half_RIGHT").performClick()
        rule.onNodeWithTag("points_RIGHT").assertTextEquals("1")
        rule.onNodeWithTag("undo").performClick()
        rule.onNodeWithTag("points_RIGHT").assertTextEquals("0")
    }

    @Test
    fun setWinShowsOverlayAndNextSetResets() {
        show(FakeMatchStore(PersistedMatch(MatchState(score = Score(bluePoints = 14, redPoints = 3)))))
        rule.onNodeWithTag("half_LEFT").performClick()
        rule.onNodeWithTag("set_won").assertIsDisplayed()
        rule.onNodeWithTag("next_set").performClick()
        rule.onNodeWithTag("points_LEFT").assertTextEquals("0")
    }
}
