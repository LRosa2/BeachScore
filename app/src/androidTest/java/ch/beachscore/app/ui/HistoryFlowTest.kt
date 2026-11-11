package ch.beachscore.app.ui

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.beachscore.app.data.PersistedMatch
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeHistoryStore
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakePlayerStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.MatchRecord
import ch.beachscore.app.score.FinishedSet
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.score.Score
import ch.beachscore.app.ui.records.RecordsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 3 main flows on the real screens, with in-memory stores. */
@RunWith(AndroidJUnit4::class)
class HistoryFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val history = FakeHistoryStore()
    private val players = FakePlayerStore("Anna", "Ben", "Carla", "Dora")

    private fun show(match: PersistedMatch? = null): ScoreboardViewModel {
        val scoreboard = ScoreboardViewModel(
            FakeMatchStore(match), FakeSettingsStore(Settings()), FakeSpeaker(), clock = SystemClock::elapsedRealtime,
            history = history, players = players,
        )
        val records = RecordsViewModel(history, players)
        rule.setContent {
            BeachScoreTheme {
                val state by scoreboard.uiState.collectAsState()
                BeachScoreRoot(scoreboard, state, records, onScreenChanged = {})
            }
        }
        rule.waitUntil(5_000) { scoreboard.uiState.value.loaded }
        return scoreboard
    }

    @Test
    fun nameAreaOpensThePickerAndNeverScores() {
        show()
        rule.onNodeWithTag("names_LEFT").performClick()
        rule.onNodeWithTag("points_LEFT").assertTextEquals("0")
        rule.onNodeWithTag("lineup_picker").assertIsDisplayed()

        rule.onNodeWithTag("chip_Anna").performClick()
        rule.onNodeWithTag("chip_Ben").performClick()
        rule.onNodeWithTag("tab_red").performClick()
        rule.onNodeWithTag("chip_Carla").performClick()
        rule.onNodeWithTag("chip_Ben").performClick() // moves Ben from Blue to Red
        rule.onNodeWithTag("lineup_done").performClick()

        rule.onNodeWithText("Anna").assertIsDisplayed()
        rule.onNodeWithText("Carla · Ben").assertIsDisplayed()
        rule.onNodeWithTag("points_LEFT").assertTextEquals("0")
        rule.onNodeWithTag("points_RIGHT").assertTextEquals("0")
    }

    @Test
    fun topOfAHalfBesideTheNameAreaStillScores() {
        show()
        // Near the top-left corner of the left half, outside the centred name pill.
        rule.onNodeWithTag("half_LEFT").performTouchInput { down(androidx.compose.ui.geometry.Offset(30.dp.toPx(), 30.dp.toPx())); up() }
        rule.onNodeWithTag("points_LEFT").assertTextEquals("1")
    }

    @Test
    fun newMatchOnTheSetWonOverlaySavesIntoHistory() {
        show(PersistedMatch(MatchState(score = Score(bluePoints = 14, redPoints = 9))))
        rule.onNodeWithTag("half_LEFT").performClick()
        rule.onNodeWithTag("next_set").assertIsDisplayed()
        rule.onNodeWithTag("overlay_new_match").performClick()
        rule.waitUntil(5_000) { history.records.value.isNotEmpty() }
        assertEquals(15 to 9, history.records.value.single().sets.single().let { it.bluePoints to it.redPoints })
        rule.onNodeWithTag("points_LEFT").assertTextEquals("0")

        rule.onNodeWithTag("history").performClick()
        rule.onNodeWithTag("history_list").assertIsDisplayed()
        rule.onNodeWithText("15:9").assertIsDisplayed()
    }

    private fun seed(n: Int) = runBlocking {
        repeat(n) { i ->
            history.add(
                MatchRecord(
                    startedAt = System.currentTimeMillis() - (i + 1) * 3_600_000L,
                    endedAt = System.currentTimeMillis() - (i + 1) * 3_600_000L + 600_000,
                    lineup = Lineup(),
                    sets = listOf(FinishedSet(1, 15, 10 + i, 15, null, null, "")),
                ),
            )
        }
    }

    @Test
    fun multiSelectAssignsPlayersToSeveralMatchesAtOnce() {
        seed(3)
        show()
        rule.onNodeWithTag("history").performClick()
        rule.onNodeWithTag("match_1").performTouchInput { longClick() }
        rule.onNodeWithTag("match_2").performClick()
        rule.onNodeWithTag("assign_players").performClick()
        rule.onNodeWithTag("chip_Anna").performClick()
        rule.onNodeWithTag("tab_red").performClick()
        rule.onNodeWithTag("chip_Dora").performClick()
        rule.onNodeWithTag("lineup_done").performClick()
        rule.waitUntil(5_000) { history.records.value.count { !it.lineup.isEmpty } == 2 }
        val byId = history.records.value.associateBy { it.id }
        assertEquals(Lineup(listOf(1), listOf(4)), byId.getValue(1).lineup)
        assertEquals(Lineup(listOf(1), listOf(4)), byId.getValue(2).lineup)
        assertTrue(byId.getValue(3).lineup.isEmpty)
    }

    @Test
    fun multiSelectDeleteAsksFirst() {
        seed(2)
        show()
        rule.onNodeWithTag("history").performClick()
        rule.onNodeWithTag("match_2").performTouchInput { longClick() }
        rule.onNodeWithTag("delete_selected").performClick()
        rule.onNodeWithTag("confirm_delete").performClick()
        rule.waitUntil(5_000) { history.records.value.size == 1 }
        assertEquals(1L, history.records.value.single().id)
    }

    @Test
    fun statisticsShowPlayersAndExcludedMatches() {
        runBlocking {
            history.add(MatchRecord(startedAt = System.currentTimeMillis(), endedAt = System.currentTimeMillis(), lineup = Lineup(listOf(1), listOf(2)), sets = listOf(FinishedSet(1, 15, 3, 15, null, null, ""))))
            history.add(MatchRecord(startedAt = System.currentTimeMillis(), endedAt = System.currentTimeMillis(), lineup = Lineup(), sets = listOf(FinishedSet(1, 15, 3, 15, null, null, ""))))
        }
        show()
        rule.onNodeWithTag("history").performClick()
        rule.onNodeWithTag("open_statistics").performClick()
        rule.onNodeWithTag("stats_table").assertIsDisplayed()
        rule.onNodeWithText("100 %").assertIsDisplayed()
        rule.onNodeWithTag("excluded_note").assertIsDisplayed()
    }

    @Test
    fun playersCanBeAddedAndRemoved() {
        show()
        rule.onNodeWithTag("history").performClick()
        rule.onNodeWithTag("open_players").performClick()
        rule.onNodeWithTag("add_player_name").performTextInput("Eva")
        rule.onNodeWithTag("add_player").performClick()
        rule.waitUntil(5_000) { players.all.value.any { it.name == "Eva" } }
        rule.onNodeWithTag("remove_Anna").performClick()
        rule.onNodeWithTag("confirm_remove").performClick()
        rule.waitUntil(5_000) { players.all.value.single { it.name == "Anna" }.archived }
    }
}
