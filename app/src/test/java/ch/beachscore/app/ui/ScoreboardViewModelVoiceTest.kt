package ch.beachscore.app.ui

import android.view.KeyEvent
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.fakes.FakeVoice
import ch.beachscore.app.input.HardwareKeyInput
import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.input.Side
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.score.Team
import ch.beachscore.app.voice.RejectReason
import ch.beachscore.app.voice.Verdict
import ch.beachscore.app.voice.VoiceSensitivity
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreboardViewModelVoiceTest {

    private var now = 1_000_000L
    private val matchStore = FakeMatchStore()
    private val settingsStore = FakeSettingsStore(Settings(language = Language.EN, voiceCommands = true))
    private val speaker = FakeSpeaker()
    private val keys = HardwareKeyInput()
    private val voice = FakeVoice()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(foreground: Boolean = true, permission: Boolean = true): ScoreboardViewModel =
        ScoreboardViewModel(matchStore, settingsStore, speaker, clock = { now }, hardwareKeys = keys, voice = voice)
            .also {
                runCurrent()
                it.onMicPermission(permission)
                it.onForegroundChanged(foreground)
            }

    private val ScoreboardViewModel.ui get() = uiState.value
    private fun ScoreboardViewModel.points(team: Team) =
        if (ui.left.team == team) ui.left.points else ui.right.points

    private fun point(team: Team) = Verdict.Accepted(ScoreCommand.TeamPoint(team))
    private val ScoreboardViewModel.lastVerdict get() = ui.voice.lastHeard?.verdict

    @Test
    fun `voice names the team, not the side, also after swapping`() = runTest {
        val vm = viewModel()
        vm.onSwapSides()
        voice.hear("point blue", point(Team.BLUE))
        assertEquals(1, vm.points(Team.BLUE))
        assertEquals(1, vm.ui.right.points, "blue is on the right after the swap")
        assertEquals("one, zero", speaker.lastText)
        assertEquals(point(Team.BLUE), vm.lastVerdict)
    }

    @Test
    fun `voice points go through the same tap lock as touch and keys`() = runTest {
        settingsStore.update { it.copy(hardwareButtons = true) }
        val vm = viewModel()
        vm.onTap(Side.LEFT)
        now += 300
        voice.hear("point red", point(Team.RED))
        assertEquals(0, vm.points(Team.RED))
        assertEquals(Verdict.Rejected(RejectReason.LOCKED), vm.lastVerdict)

        now += 1500
        voice.hear("point red", point(Team.RED))
        assertEquals(1, vm.points(Team.RED))
        now += 100
        keys.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0)
        vm.onTap(Side.LEFT)
        assertEquals(1, vm.points(Team.BLUE), "voice point locks touch and keys too")
    }

    @Test
    fun `voice undo is not locked`() = runTest {
        val vm = viewModel()
        voice.hear("point blue", point(Team.BLUE))
        voice.hear("undo", Verdict.Accepted(ScoreCommand.Undo))
        assertEquals(0, vm.points(Team.BLUE))
        assertEquals("Correction, zero, zero", speaker.lastText)
        assertEquals(Verdict.Accepted(ScoreCommand.Undo), vm.lastVerdict)
    }

    @Test
    fun `undo with nothing to undo is reported`() = runTest {
        val vm = viewModel()
        voice.hear("undo", Verdict.Accepted(ScoreCommand.Undo))
        assertEquals(Verdict.Rejected(RejectReason.NOTHING_TO_UNDO), vm.lastVerdict)
    }

    @Test
    fun `set won overlay ignores voice points but not undo`() = runTest {
        matchStore.saved = PersistedMatch(MatchState(score = Score(bluePoints = 14, redPoints = 3)))
        val vm = viewModel()
        voice.hear("point blue", point(Team.BLUE))
        val won = vm.ui.setWon
        assertEquals(Team.BLUE, won?.winner)

        now += 5000
        voice.hear("point red", point(Team.RED))
        assertEquals(3, vm.points(Team.RED))
        assertEquals(Verdict.Rejected(RejectReason.SET_WON), vm.lastVerdict)

        voice.hear("undo", Verdict.Accepted(ScoreCommand.Undo))
        assertNull(vm.ui.setWon)
        assertEquals(14, vm.points(Team.BLUE))
    }

    @Test
    fun `rejected phrases change nothing and say nothing`() = runTest {
        val vm = viewModel()
        voice.hear("[unk]", Verdict.Rejected(RejectReason.UNKNOWN), 0.4)
        voice.hear("point blue", Verdict.Rejected(RejectReason.LOW_CONFIDENCE), 0.5)
        assertEquals(0, vm.points(Team.BLUE))
        assertEquals(0, speaker.spoken.size)
        val last = vm.ui.voice.lastHeard!!
        assertEquals("point blue", last.text)
        assertEquals(0.5, last.minConfidence)
        assertEquals(Verdict.Rejected(RejectReason.LOW_CONFIDENCE), last.verdict)
    }

    @Test
    fun `diagnostics line can be switched off`() = runTest {
        settingsStore.update { it.copy(showHeard = false) }
        val vm = viewModel()
        voice.hear("point blue", point(Team.BLUE))
        assertEquals(1, vm.points(Team.BLUE))
        assertNull(vm.ui.voice.lastHeard)
    }

    @Test
    fun `diagnostics line fades after a few seconds`() = runTest {
        val vm = viewModel()
        voice.hear("[unk]", Verdict.Rejected(RejectReason.UNKNOWN))
        advanceTimeBy(ScoreboardViewModel.HEARD_VISIBLE_MS + 1)
        assertNull(vm.ui.voice.lastHeard)
    }

    @Test
    fun `listens only with setting on, permission granted and app in the foreground`() = runTest {
        val vm = viewModel(foreground = false)
        assertNull(voice.listening, "background")
        vm.onForegroundChanged(true)
        assertEquals(Language.EN to VoiceSensitivity.NORMAL, voice.listening)
        vm.onForegroundChanged(false)
        assertNull(voice.listening, "stops in the background")
        vm.onForegroundChanged(true)
        vm.onMicPermission(false)
        assertNull(voice.listening, "permission revoked")
        vm.onMicPermission(true)
        settingsStore.update { it.copy(voiceCommands = false) }
        assertNull(voice.listening, "setting off")
    }

    @Test
    fun `permission revoked while voice is on turns the setting off with an explanation`() = runTest {
        val vm = viewModel(permission = false)
        assertEquals(false, vm.ui.settings.voiceCommands)
        assertEquals(true, vm.ui.voicePermissionDenied)
        assertNull(voice.listening)
    }

    @Test
    fun `granting the permission from the settings switch turns voice on`() = runTest {
        settingsStore.update { it.copy(voiceCommands = false) }
        val vm = viewModel(permission = false)
        assertEquals(false, vm.ui.voicePermissionDenied, "nothing to explain while voice is off")
        vm.onMicPermissionResult(granted = false)
        assertEquals(false, vm.ui.settings.voiceCommands)
        assertEquals(true, vm.ui.voicePermissionDenied)
        vm.onMicPermissionResult(granted = true)
        assertEquals(true, vm.ui.settings.voiceCommands)
        assertEquals(false, vm.ui.voicePermissionDenied)
        assertEquals(Language.EN to VoiceSensitivity.NORMAL, voice.listening)
    }

    @Test
    fun `voice off by default never starts listening`() = runTest {
        settingsStore.update { Settings(language = Language.EN) }
        viewModel()
        assertNull(voice.listening)
        assertEquals(0, voice.startCount)
    }

    @Test
    fun `recognition follows the app language and sensitivity`() = runTest {
        val vm = viewModel()
        settingsStore.update { it.copy(language = Language.DE) }
        assertEquals(Language.DE to VoiceSensitivity.NORMAL, voice.listening)
        settingsStore.update { it.copy(voiceSensitivity = VoiceSensitivity.STRICT) }
        assertEquals(Language.DE to VoiceSensitivity.STRICT, voice.listening)
        assertEquals(VoiceSensitivity.STRICT, vm.ui.settings.voiceSensitivity)
    }

    @Test
    fun `voice status is exposed for the mic indicator`() = runTest {
        val vm = viewModel()
        assertEquals(ch.beachscore.app.voice.VoiceStatus.Listening, vm.ui.voice.status)
    }
}
