package ch.beachscore.app.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import ch.beachscore.app.input.RemoteAction
import ch.beachscore.app.input.RemoteKeyInput
import ch.beachscore.app.input.RemoteKeys
import ch.beachscore.app.score.Team
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3.4.0: the "Remote buttons" section, learn mode and the confirmation pill (English labels). */
@RunWith(AndroidJUnit4::class)
class RemoteButtonsTest {

    @get:Rule
    val rule = createComposeRule()

    private val keys = RemoteKeyInput()
    private lateinit var vm: ScoreboardViewModel

    private fun show(settings: FakeSettingsStore) {
        vm = ScoreboardViewModel(FakeMatchStore(), settings, FakeSpeaker(), clock = SystemClock::elapsedRealtime, remoteKeys = keys)
        rule.setContent {
            BeachScoreTheme {
                val state by vm.uiState.collectAsState()
                ScoreboardRoute(vm, state)
            }
        }
        rule.waitUntil(5_000) { vm.uiState.value.loaded }
    }

    /** What the activity does for a key press; returns whether the key down was consumed. */
    private fun press(code: Int): Boolean {
        var consumed = false
        rule.runOnIdle {
            consumed = keys.onKey(code, KeyEvent.ACTION_DOWN, 0)
            keys.onKey(code, KeyEvent.ACTION_UP, 0)
        }
        return consumed
    }

    private fun points(team: Team) = vm.uiState.value.let { if (it.left.team == team) it.left.points else it.right.points }

    @Test
    fun assignClearAndCancel() {
        val settings = FakeSettingsStore(Settings())
        show(settings)
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithText("Remote buttons").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("remote_keys_blue").performScrollTo().assertTextEquals("Not assigned")
        rule.onNodeWithTag("remote_clear_blue").assertIsNotEnabled()

        rule.onNodeWithTag("remote_assign_blue").performClick()
        rule.onNodeWithTag("remote_keys_blue").assertTextEquals("Press a button on the remote…")
        assertTrue("consumed while learning", press(KeyEvent.KEYCODE_MEDIA_NEXT))
        rule.onNodeWithTag("remote_keys_blue").assertTextEquals("Next track")
        assertEquals(0, points(Team.BLUE))

        // A second button for Blue, then the same button moves to Undo.
        rule.onNodeWithTag("remote_assign_blue").performClick()
        press(KeyEvent.KEYCODE_VOLUME_UP)
        rule.onNodeWithTag("remote_keys_blue").assertTextEquals("Next track, Volume +")
        rule.onNodeWithTag("remote_assign_undo").performScrollTo().performClick()
        press(KeyEvent.KEYCODE_VOLUME_UP)
        rule.onNodeWithTag("remote_keys_blue").assertTextEquals("Next track")
        rule.onNodeWithTag("remote_keys_undo").assertTextEquals("Volume +")
        assertEquals(RemoteAction.UNDO, runBlocking { settings.settings.first() }.remoteKeys.actionFor(KeyEvent.KEYCODE_VOLUME_UP))

        // Cancel leaves the row as it was; a fallback name for unknown keys.
        rule.onNodeWithTag("remote_assign_red").performScrollTo().performClick()
        rule.onNodeWithTag("remote_cancel_red").performClick()
        rule.onNodeWithTag("remote_keys_red").assertTextEquals("Not assigned")
        rule.onNodeWithTag("remote_assign_red").performClick()
        press(300)
        rule.onNodeWithTag("remote_keys_red").assertTextEquals("Key 300")
        rule.onNodeWithTag("remote_clear_red").performClick()
        rule.onNodeWithTag("remote_keys_red").assertTextEquals("Not assigned")

        // While the menu is open the buttons do not score.
        assertFalse(press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(0, points(Team.BLUE))
    }

    @Test
    fun aPressScoresAndShowsAConfirmation() {
        val mapped = RemoteKeys().assign(RemoteAction.BLUE_POINT, KeyEvent.KEYCODE_MEDIA_NEXT).assign(RemoteAction.UNDO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        show(FakeSettingsStore(Settings(remoteKeys = mapped)))
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_NEXT))
        rule.waitUntil(2_000) { points(Team.BLUE) == 1 }
        rule.onNodeWithText("Remote: Blue point").assertIsDisplayed()
        press(KeyEvent.KEYCODE_MEDIA_NEXT)
        rule.onNodeWithText("✗ locked").assertIsDisplayed()
        assertEquals(1, points(Team.BLUE))
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        rule.onNodeWithText("Remote: Undo").assertIsDisplayed()
        assertEquals(0, points(Team.BLUE))
    }
}
