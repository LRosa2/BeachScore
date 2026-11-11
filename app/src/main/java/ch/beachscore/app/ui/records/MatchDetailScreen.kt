package ch.beachscore.app.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.R
import ch.beachscore.app.history.Player
import ch.beachscore.app.score.Team

/** One match: lineups (editable), sets with scores and durations, delete. */
@Composable
fun MatchDetailScreen(state: RecordsUiState, vm: RecordsViewModel, matchId: Long, onBack: () -> Unit) {
    val match = state.matches.firstOrNull { it.id == matchId }
    // Deleted (here or elsewhere): nothing to show, go back.
    LaunchedEffect(match == null, state.loaded) { if (state.loaded && match == null) onBack() }
    if (match == null) return
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    fun of(team: Team) = match.lineup.of(team).map { state.players[it] ?: Player(it, "?", true) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RecordsTopBar(formatDate(localDate(match.startedAt)) + "  " + formatTime(match.startedAt), onBack = onBack) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete_match")) { Text(stringResource(R.string.delete_match)) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TeamLabel(Team.BLUE, of(Team.BLUE), match.winner == Team.BLUE)
                Text(stringResource(R.string.vs))
                TeamLabel(Team.RED, of(Team.RED), match.winner == Team.RED)
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("edit_players")) { Text(stringResource(R.string.edit_players)) }
            }
            if (match.winner == null) Text(stringResource(R.string.draw), style = MaterialTheme.typography.titleMedium)
            for (set in match.sets) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_n, set.index), fontSize = 20.sp, modifier = Modifier.width(120.dp))
                    Text("${set.bluePoints}:${set.redPoints}", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
                    Text(formatDuration(set.startedAt, set.endedAt), fontSize = 18.sp)
                }
            }
        }
    }

    if (editing) {
        AssignPlayersPicker(
            initial = match.lineup,
            players = state.activePlayers,
            vm = vm,
            onDone = { lineup ->
                vm.assign(listOf(match.id), lineup)
                editing = false
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.delete_match_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(listOf(match.id)) }, modifier = Modifier.testTag("confirm_delete")) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
