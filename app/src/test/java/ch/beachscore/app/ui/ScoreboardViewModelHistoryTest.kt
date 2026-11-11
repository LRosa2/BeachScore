package ch.beachscore.app.ui

import ch.beachscore.app.data.AddPlayerResult
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeHistoryStore
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakePlayerStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.input.Side
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.score.Team
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreboardViewModelHistoryTest {

    private var now = 1_000_000L // tap-lock clock
    private var wall = 1_762_000_000_000L // wall clock for history
    private val matchStore = FakeMatchStore()
    private val settingsStore = FakeSettingsStore(Settings(language = Language.EN, tapLockMs = 500))
    private val speaker = FakeSpeaker()
    private val history = FakeHistoryStore()
    private val players = FakePlayerStore("Anna", "Ben", "Carla")

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = ScoreboardViewModel(
        matchStore, settingsStore, speaker, clock = { now },
        history = history, players = players, wallClock = { wall },
    ).also { runCurrent() }

    private val ScoreboardViewModel.ui get() = uiState.value

    /** Taps a side [n] times, past the tap lock each time, 10 s apart on the wall clock. */
    private fun ScoreboardViewModel.tap(side: Side, n: Int = 1) = repeat(n) {
        now += 1000
        wall += 10_000
        onTap(side)
    }

    @Test
    fun `new match on the set-won overlay saves the match with the set just won`() = runTest {
        val vm = viewModel()
        vm.togglePlayer(Team.BLUE, 1)
        vm.tap(Side.LEFT, 15)
        val firstPoint = 1_762_000_010_000L
        vm.onNewMatch()

        val saved = history.records.value.single()
        assertEquals(listOf(15 to 0), saved.sets.map { it.bluePoints to it.redPoints })
        assertEquals(firstPoint, saved.startedAt)
        assertEquals(firstPoint + 140_000, saved.endedAt)
        assertEquals(Lineup(blue = listOf(1)), saved.lineup)
        assertEquals("B".repeat(15), saved.sets.single().sequence)

        assertNull(vm.ui.setWon)
        assertEquals(0, vm.ui.left.points + vm.ui.left.sets + vm.ui.right.sets)
        assertEquals(Lineup(blue = listOf(1)), vm.ui.lineup, "lineups persist across matches")
    }

    @Test
    fun `hold-to-reset mid-set saves only finished sets`() = runTest {
        val vm = viewModel()
        vm.tap(Side.RIGHT, 15)
        vm.onNextSet()
        vm.tap(Side.LEFT, 4)
        vm.onNewMatch()
        assertEquals(listOf(0 to 15), history.records.value.single().sets.map { it.bluePoints to it.redPoints })
    }

    @Test
    fun `new match without a finished set saves nothing`() = runTest {
        val vm = viewModel()
        vm.tap(Side.LEFT, 7)
        vm.onNewMatch()
        assertTrue(history.records.value.isEmpty())
        assertEquals(0, vm.ui.left.points)
    }

    @Test
    fun `undo of the set-winning point keeps the record consistent`() = runTest {
        val vm = viewModel()
        vm.tap(Side.LEFT, 15)
        vm.onUndo()
        vm.tap(Side.RIGHT, 1)
        vm.onNewMatch()
        assertTrue(history.records.value.isEmpty(), "14:1, no finished set")
    }

    @Test
    fun `names follow their team when sides are swapped`() = runTest {
        val vm = viewModel()
        vm.togglePlayer(Team.BLUE, 1)
        vm.togglePlayer(Team.BLUE, 2)
        vm.togglePlayer(Team.RED, 3)
        assertEquals(listOf("Anna", "Ben"), vm.ui.left.players.map { it.name })
        vm.onSwapSides()
        assertEquals(listOf("Carla"), vm.ui.left.players.map { it.name })
        assertEquals(listOf("Anna", "Ben"), vm.ui.right.players.map { it.name })
    }

    @Test
    fun `lineup is persisted with the running match and restored`() = runTest {
        viewModel().togglePlayer(Team.RED, 2)
        assertEquals(Lineup(red = listOf(2)), matchStore.saved?.lineup)
        assertEquals(Lineup(red = listOf(2)), viewModel().ui.lineup)
    }

    @Test
    fun `lineup changed mid-match applies to the whole match`() = runTest {
        val vm = viewModel()
        vm.tap(Side.LEFT, 15)
        vm.onNextSet()
        vm.tap(Side.LEFT, 15)
        vm.togglePlayer(Team.RED, 3)
        vm.onNewMatch()
        assertEquals(Lineup(red = listOf(3)), history.records.value.single().lineup)
        assertEquals(2, history.records.value.single().sets.size)
    }

    @Test
    fun `archived players leave the running lineup and the picker`() = runTest {
        val vm = viewModel()
        vm.togglePlayer(Team.BLUE, 1)
        players.archive(1)
        assertEquals(Lineup(), vm.ui.lineup)
        assertEquals(listOf("Ben", "Carla"), vm.ui.activePlayers.map { it.name })
    }

    @Test
    fun `adding a player in the picker puts them on the team`() = runTest {
        val vm = viewModel()
        var result: AddPlayerResult? = null
        vm.addPlayer(" Dora ", Team.RED) { result = it }
        val dora = (result as AddPlayerResult.Added).player
        assertEquals(Lineup(red = listOf(dora.id)), vm.ui.lineup)
        vm.addPlayer("dora", Team.RED) { result = it }
        assertTrue(result is AddPlayerResult.Rejected)
    }

    @Test
    fun `a match running before the update closes into history`() = runTest {
        // What the migration hands over: a finished set without times and a running set.
        matchStore.saved = PersistedMatch(
            MatchState(
                score = Score(bluePoints = 2, redPoints = 0, blueSets = 1),
                finishedSets = listOf(ch.beachscore.app.score.FinishedSet(1, 15, 9, 15, null, null, "B".repeat(15))),
                currentSequence = "BB",
                history = List(26) { Score() },
            ),
        )
        val vm = viewModel()
        vm.onNewMatch()
        val saved = history.records.value.single()
        assertEquals(wall, saved.startedAt)
        assertEquals(15 to 9, saved.sets.single().let { it.bluePoints to it.redPoints })
    }
}
