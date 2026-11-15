package ch.beachscore.app.ui

import android.view.KeyEvent
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.input.RemoteAction
import ch.beachscore.app.input.RemoteKeyInput
import ch.beachscore.app.input.RemoteKeys
import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.input.Side
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.score.Team
import ch.beachscore.app.voice.RejectReason
import ch.beachscore.app.voice.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 3.4.0: remote buttons through the view model (tap lock, undo, panels, learning, migration). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreboardViewModelRemoteTest {

    private val next = KeyEvent.KEYCODE_MEDIA_NEXT
    private val prev = KeyEvent.KEYCODE_MEDIA_PREVIOUS
    private val play = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    private var now = 1_000_000L
    private val matchStore = FakeMatchStore()
    private val mapped = RemoteKeys().assign(RemoteAction.BLUE_POINT, next).assign(RemoteAction.RED_POINT, prev).assign(RemoteAction.UNDO, play)
    private val settingsStore = FakeSettingsStore(Settings(language = Language.EN, remoteKeys = mapped))
    private val speaker = FakeSpeaker()
    private val keys = RemoteKeyInput()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): ScoreboardViewModel =
        ScoreboardViewModel(matchStore, settingsStore, speaker, clock = { now }, remoteKeys = keys).also { runCurrent() }

    private val ScoreboardViewModel.ui get() = uiState.value
    private fun ScoreboardViewModel.points(team: Team) = if (ui.left.team == team) ui.left.points else ui.right.points

    /** One press; returns whether the key down was consumed. */
    private fun press(code: Int): Boolean {
        val consumed = keys.onKey(code, KeyEvent.ACTION_DOWN, 0)
        keys.onKey(code, KeyEvent.ACTION_UP, 0)
        return consumed
    }

    @Test
    fun `buttons score for their team and the score is spoken`() = runTest {
        val vm = viewModel()
        assertTrue(press(prev))
        assertEquals(1, vm.points(Team.RED))
        assertEquals("one, zero", speaker.lastText)
    }

    @Test
    fun `actions follow the team after swapping sides`() = runTest {
        val vm = viewModel()
        vm.onSwapSides()
        press(next)
        assertEquals(Team.BLUE, vm.ui.right.team)
        assertEquals(1, vm.ui.right.points)
    }

    @Test
    fun `the tap lock applies across touch and remote, undo ends it`() = runTest {
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        now += 100
        press(prev)
        assertEquals(0, vm.points(Team.RED), "locked")
        press(play)
        assertEquals(0, vm.points(Team.BLUE), "undone")
        assertFalse(vm.ui.locked)
        press(prev)
        assertEquals(1, vm.points(Team.RED), "the right team at once")
        now += 100
        vm.onTap(Side.LEFT)
        assertEquals(0, vm.points(Team.BLUE), "remote point locks touch too")
    }

    @Test
    fun `ignored while a panel or another screen is open`() = runTest {
        val vm = viewModel()
        vm.onPanelOpen(true)
        assertFalse(press(next), "the key keeps its normal meaning")
        assertEquals(0, vm.points(Team.BLUE))
        vm.onPanelOpen(false)
        vm.onScoreboardVisible(false)
        assertFalse(press(next))
        vm.onScoreboardVisible(true)
        assertTrue(press(next))
        assertEquals(1, vm.points(Team.BLUE))
    }

    @Test
    fun `with the set won overlay points are blocked and undo works`() = runTest {
        matchStore.saved = PersistedMatch(MatchState(score = Score(bluePoints = 14, redPoints = 12)))
        val vm = viewModel()
        press(next)
        assertTrue(vm.ui.setWon != null)
        now += 5000
        press(prev)
        assertEquals(12, vm.points(Team.RED))
        assertEquals(Verdict.Rejected(RejectReason.SET_WON), vm.ui.remotePress?.verdict)
        press(play)
        assertNull(vm.ui.setWon)
        assertEquals(14, vm.points(Team.BLUE))
    }

    @Test
    fun `a press shows a short confirmation`() = runTest {
        val vm = viewModel()
        press(next)
        val shown = vm.ui.remotePress!!
        assertEquals(RemoteAction.BLUE_POINT, shown.action)
        assertEquals(Verdict.Accepted(ScoreCommand.TeamPoint(Team.BLUE)), shown.verdict)
        now += 100
        press(next)
        assertEquals(Verdict.Rejected(RejectReason.LOCKED), vm.ui.remotePress!!.verdict)
        assertTrue(vm.ui.remotePress!!.seq > shown.seq)
        advanceTimeBy(ScoreboardViewModel.REMOTE_VISIBLE_MS + 1)
        assertNull(vm.ui.remotePress)
    }

    @Test
    fun `learn assigns the next key, moving it from another action`() = runTest {
        val vm = viewModel()
        vm.onPanelOpen(true)
        vm.startLearning(RemoteAction.UNDO)
        assertEquals(RemoteAction.UNDO, vm.ui.learning)
        assertTrue(press(next), "consumed while learning")
        runCurrent()
        assertNull(vm.ui.learning)
        val stored = vm.ui.settings.remoteKeys
        assertEquals(RemoteAction.UNDO, stored.actionFor(next))
        assertTrue(stored.keysFor(RemoteAction.BLUE_POINT).isEmpty())
        assertEquals(0, vm.points(Team.BLUE), "learning never scores")
        // A second button for the same action.
        vm.startLearning(RemoteAction.UNDO)
        press(KeyEvent.KEYCODE_VOLUME_UP)
        runCurrent()
        assertEquals(listOf(play, next, KeyEvent.KEYCODE_VOLUME_UP), vm.ui.settings.remoteKeys.keysFor(RemoteAction.UNDO))
    }

    @Test
    fun `cancel, closing the panel and clear`() = runTest {
        val vm = viewModel()
        vm.onPanelOpen(true)
        vm.startLearning(RemoteAction.RED_POINT)
        vm.cancelLearning()
        assertNull(vm.ui.learning)
        assertFalse(press(KeyEvent.KEYCODE_A))
        vm.startLearning(RemoteAction.RED_POINT)
        vm.onPanelOpen(false)
        assertNull(vm.ui.learning, "closing the menu ends learning")
        assertNull(keys.learning)
        vm.clearRemote(RemoteAction.RED_POINT)
        runCurrent()
        assertTrue(vm.ui.settings.remoteKeys.keysFor(RemoteAction.RED_POINT).isEmpty())
        assertFalse(press(prev), "cleared keys pass through")
    }

    @Test
    fun `the old volume key toggle becomes volume buttons for the teams on screen`() = runTest {
        settingsStore.update { Settings(language = Language.EN, legacyVolumeKeys = true) }
        matchStore.saved = PersistedMatch(MatchState.new(15), blueOnLeft = false)
        val vm = viewModel()
        val migrated = settingsStore.settings.value
        assertFalse(migrated.legacyVolumeKeys)
        assertEquals(RemoteAction.RED_POINT, migrated.remoteKeys.actionFor(KeyEvent.KEYCODE_VOLUME_UP), "Red was on the left")
        assertEquals(RemoteAction.BLUE_POINT, migrated.remoteKeys.actionFor(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(1, vm.points(Team.RED))
    }

    @Test
    fun `no migration without the old toggle`() = runTest {
        settingsStore.update { Settings(language = Language.EN) }
        viewModel()
        assertTrue(settingsStore.settings.value.remoteKeys.isEmpty)
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
