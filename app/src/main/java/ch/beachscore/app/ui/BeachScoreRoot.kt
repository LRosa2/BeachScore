package ch.beachscore.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.beachscore.app.ui.records.CsvShare
import ch.beachscore.app.ui.records.HistoryScreen
import ch.beachscore.app.ui.records.MatchDetailScreen
import ch.beachscore.app.ui.records.PlayersScreen
import ch.beachscore.app.ui.records.RecordsViewModel
import ch.beachscore.app.ui.records.StatisticsScreen

enum class Screen { SCOREBOARD, HISTORY, MATCH, PLAYERS, STATISTICS }

/**
 * Screen switching without a navigation library. The scoreboard is the home; history, players
 * and statistics are opened from its menu. System Back goes up one level (match → history,
 * everything else → scoreboard).
 */
@Composable
fun BeachScoreRoot(
    scoreboard: ScoreboardViewModel,
    scoreboardState: ScoreboardUiState,
    records: RecordsViewModel,
    onScreenChanged: (Screen) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.SCOREBOARD) }
    var matchId by rememberSaveable { mutableLongStateOf(-1L) }
    val context = LocalContext.current
    LaunchedEffect(screen) { onScreenChanged(screen) }

    val up = {
        screen = when (screen) {
            Screen.MATCH -> Screen.HISTORY
            else -> Screen.SCOREBOARD
        }
    }
    BackHandler(enabled = screen != Screen.SCOREBOARD) {
        if (screen == Screen.HISTORY && records.state.value.options.selection.isNotEmpty()) records.clearSelection() else up()
    }

    if (screen == Screen.SCOREBOARD) {
        ScoreboardRoute(scoreboard, scoreboardState, onOpen = { entry ->
            when (entry) {
                MenuEntry.HISTORY -> screen = Screen.HISTORY
                MenuEntry.PLAYERS -> screen = Screen.PLAYERS
                MenuEntry.STATISTICS -> screen = Screen.STATISTICS
                MenuEntry.SETTINGS, MenuEntry.ABOUT -> Unit
            }
        })
        return
    }
    val state by records.state.collectAsStateWithLifecycle()
    // A Surface sets the content colour, so plain Text is readable on the dark background.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    when (screen) {
        Screen.HISTORY -> HistoryScreen(
            state, records,
            onBack = up,
            onOpenMatch = { matchId = it; screen = Screen.MATCH },
            onExport = { ids -> CsvShare.share(context, records.csv(ids)) },
        )
        Screen.MATCH -> MatchDetailScreen(state, records, matchId, onBack = up)
        Screen.PLAYERS -> PlayersScreen(state, records, onBack = up)
        Screen.STATISTICS -> StatisticsScreen(state, records, onBack = up)
        Screen.SCOREBOARD -> Unit
    }
    }
}
