package ch.beachscore.app.ui

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScoreboardSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(matchStore: FakeMatchStore = FakeMatchStore()): ScoreboardViewModel {
        val vm = ScoreboardViewModel(matchStore, FakeSettingsStore(), FakeSpeaker(), clock = SystemClock::elapsedRealtime)
        rule.setContent {
            BeachScoreTheme {
                val state by vm.uiState.collectAsState()
                ScoreboardRoute(vm, state)
            }
        }
        rule.waitUntil(5_000) { vm.uiState.value.loaded }
        return vm
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
