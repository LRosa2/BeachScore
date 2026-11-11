package ch.beachscore.app.ui

import android.view.KeyEvent
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.input.HardwareKeyInput
import ch.beachscore.app.input.Side
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.score.Team
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

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreboardViewModelTest {

    private var now = 1_000_000L
    private val matchStore = FakeMatchStore()
    private val settingsStore = FakeSettingsStore(Settings(language = Language.EN))
    private val speaker = FakeSpeaker()
    private val keys = HardwareKeyInput()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): ScoreboardViewModel =
        ScoreboardViewModel(matchStore, settingsStore, speaker, clock = { now }, hardwareKeys = keys)
            .also { runCurrent() }

    private val ScoreboardViewModel.ui get() = uiState.value
    private fun ScoreboardViewModel.points(team: Team) =
        if (ui.left.team == team) ui.left.points else ui.right.points

    private fun pressVolume(keyCode: Int) {
        keys.onKey(keyCode, KeyEvent.ACTION_DOWN, 0)
        keys.onKey(keyCode, KeyEvent.ACTION_UP, 0)
    }

    @Test
    fun `starts with blue left and red right at zero`() = runTest {
        val vm = viewModel()
        assertTrue(vm.ui.loaded)
        assertEquals(Team.BLUE, vm.ui.left.team)
        assertEquals(Team.RED, vm.ui.right.team)
        assertEquals(0, vm.ui.left.points)
        assertFalse(vm.ui.canUndo)
    }

    @Test
    fun `tap adds one point and speaks the score`() = runTest {
        val vm = viewModel()
        vm.onTap(Side.RIGHT)
        assertEquals(1, vm.points(Team.RED))
        assertEquals("one, zero", speaker.lastText)
        assertTrue(vm.ui.locked)
        assertEquals(Team.RED, vm.ui.lastPoint?.team)
    }

    @Test
    fun `lock applies across both sides`() = runTest {
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        now += 100
        vm.onTap(Side.LEFT)
        vm.onTap(Side.RIGHT)
        assertEquals(1, vm.points(Team.BLUE))
        assertEquals(0, vm.points(Team.RED))
        assertEquals(1, speaker.spoken.size)
    }

    @Test
    fun `lock applies across input sources`() = runTest {
        settingsStore.update { it.copy(hardwareButtons = true) }
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        now += 1000
        pressVolume(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(0, vm.points(Team.RED), "hardware key ignored during touch lock")

        now += 500
        pressVolume(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(1, vm.points(Team.RED), "accepted once the lock has passed")
        now += 10
        vm.onTap(Side.LEFT)
        assertEquals(1, vm.points(Team.BLUE), "touch ignored during hardware-key lock")
    }

    @Test
    fun `lock ends after the configured duration`() = runTest {
        settingsStore.update { it.copy(tapLockMs = 500) }
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        now += 500
        advanceTimeBy(501)
        assertFalse(vm.ui.locked)
        vm.onTap(Side.LEFT)
        assertEquals(2, vm.points(Team.BLUE))
    }

    @Test
    fun `hardware keys only score when enabled`() = runTest {
        val vm = viewModel()
        assertFalse(keys.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0))
        assertEquals(0, vm.points(Team.BLUE))
        settingsStore.update { it.copy(hardwareButtons = true) }
        pressVolume(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(1, vm.points(Team.BLUE))
    }

    @Test
    fun `swap sides maps touch to the team shown on that side`() = runTest {
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        vm.onSwapSides()
        assertEquals(Team.RED, vm.ui.left.team)
        assertEquals(Team.BLUE, vm.ui.right.team)
        assertEquals(1, vm.ui.right.points, "score follows the team")
        now += 2000
        vm.onTap(Side.LEFT)
        assertEquals(1, vm.points(Team.RED))
        assertEquals(1, vm.points(Team.BLUE))
    }

    @Test
    fun `swap sides maps hardware keys to the team shown on that side`() = runTest {
        settingsStore.update { it.copy(hardwareButtons = true) }
        val vm = viewModel()
        vm.onSwapSides()
        pressVolume(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(1, vm.points(Team.RED), "volume up scores for the left side, now Red")
    }

    @Test
    fun `undo is not blocked by the lock and announces a correction`() = runTest {
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        vm.onUndo()
        assertEquals(0, vm.points(Team.BLUE))
        assertEquals("Correction, zero, zero", speaker.lastText)
        assertFalse(vm.ui.canUndo)
        assertFalse(vm.ui.locked, "undoing the point ends its lock")
        vm.onTap(Side.RIGHT)
        assertEquals(1, vm.points(Team.RED), "the right team can be credited right away")
    }

    @Test
    fun `set win shows the overlay and blocks scoring until next set`() = runTest {
        matchStore.saved = PersistedMatch(MatchState(score = Score(bluePoints = 14, redPoints = 12)))
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        val won = checkNotNull(vm.ui.setWon) { "set won overlay expected" }
        assertEquals(Team.BLUE, won.winner)
        assertEquals(15, won.winnerPoints)
        assertEquals(12, won.loserPoints)
        assertEquals("Set Blue, fifteen, twelve", speaker.lastText)

        now += 5000
        vm.onTap(Side.RIGHT)
        assertEquals(12, vm.points(Team.RED), "scoring blocked while the overlay is shown")

        vm.onNextSet()
        assertNull(vm.ui.setWon)
        assertEquals(0, vm.ui.left.points)
        assertEquals(1, vm.ui.left.sets)
    }

    @Test
    fun `undo from the set won overlay returns to the running set`() = runTest {
        matchStore.saved = PersistedMatch(MatchState(score = Score(bluePoints = 14, redPoints = 12)))
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        vm.onUndo()
        assertNull(vm.ui.setWon)
        assertEquals(14, vm.points(Team.BLUE))
        assertEquals(0, vm.ui.left.sets)
    }

    @Test
    fun `announce off keeps silent`() = runTest {
        settingsStore.update { it.copy(announce = false) }
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        vm.onUndo()
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `speech uses the selected language`() = runTest {
        settingsStore.update { it.copy(language = Language.DE) }
        val vm = viewModel()
        assertEquals(Language.DE, speaker.prepared)
        vm.onTap(Side.LEFT)
        assertEquals("eins, null" to Language.DE, speaker.spoken.last())
    }

    @Test
    fun `every change is persisted and restored by a new view model`() = runTest {
        val first = viewModel()
        first.onTap(Side.LEFT)
        first.onSwapSides()
        val saved = checkNotNull(matchStore.saved) { "match was not saved" }
        assertEquals(1, saved.state.score.bluePoints)
        assertFalse(saved.blueOnLeft)

        val second = viewModel()
        assertEquals(Team.RED, second.ui.left.team)
        assertEquals(1, second.points(Team.BLUE))
        assertTrue(second.ui.canUndo, "undo history survives a restart")
    }

    @Test
    fun `fresh match uses the configured points per set`() = runTest {
        settingsStore.update { it.copy(pointsPerSet = 21) }
        matchStore.saved = PersistedMatch(MatchState(score = Score(bluePoints = 14, redPoints = 12)))
        viewModel().run {
            onTap(Side.LEFT)
            assertNull(ui.setWon, "15:12 is no win at 21")
        }
    }

    @Test
    fun `changing points per set updates the running match`() = runTest {
        val vm = viewModel()
        vm.updateSettings { it.copy(pointsPerSet = 21) }
        assertEquals(21, vm.ui.settings.pointsPerSet)
        assertEquals(21, matchStore.saved?.state?.target)
    }

    @Test
    fun `new match resets points and sets`() = runTest {
        matchStore.saved = PersistedMatch(MatchState(score = Score(3, 4, 1, 1)))
        val vm = viewModel()
        vm.onNewMatch()
        assertEquals(0, vm.ui.left.points + vm.ui.right.points + vm.ui.left.sets + vm.ui.right.sets)
        assertFalse(vm.ui.canUndo)
    }

    @Test
    fun `test voice speaks even when announcements are off`() = runTest {
        settingsStore.update { it.copy(announce = false) }
        val vm = viewModel()
        vm.onTestVoice()
        assertEquals("zero, zero", speaker.lastText)
    }
}
