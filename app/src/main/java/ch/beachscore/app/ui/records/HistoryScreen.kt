package ch.beachscore.app.ui.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.R
import ch.beachscore.app.data.AddPlayerResult
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.MatchRecord
import ch.beachscore.app.history.Player
import ch.beachscore.app.score.Team
import ch.beachscore.app.ui.LineupPicker
import ch.beachscore.app.ui.color

/**
 * Closed matches, newest first, grouped by day. Tap opens a match; long-press starts
 * multi-select with "Assign players", "Export CSV" and "Delete".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    state: RecordsUiState,
    vm: RecordsViewModel,
    onBack: () -> Unit,
    onOpenMatch: (Long) -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenPlayers: () -> Unit,
    onExport: (ids: Set<Long>?) -> Unit,
) {
    val selection = state.options.selection
    val selecting = selection.isNotEmpty()
    var assigning by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (selecting) {
            RecordsTopBar(stringResource(R.string.selected_count, selection.size), onBack = vm::clearSelection) {
                TextButton(onClick = { assigning = true }, modifier = Modifier.testTag("assign_players")) { Text(stringResource(R.string.assign_players)) }
                TextButton(onClick = { onExport(selection) }) { Text(stringResource(R.string.export_csv)) }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete_selected")) { Text(stringResource(R.string.delete)) }
            }
        } else {
            RecordsTopBar(stringResource(R.string.history), onBack = onBack) {
                TextButton(onClick = onOpenStatistics, modifier = Modifier.testTag("open_statistics")) { Text(stringResource(R.string.statistics)) }
                TextButton(onClick = onOpenPlayers, modifier = Modifier.testTag("open_players")) { Text(stringResource(R.string.players)) }
                TextButton(onClick = { onExport(null) }, enabled = state.matches.isNotEmpty()) { Text(stringResource(R.string.export_csv)) }
            }
        }

        if (state.loaded && state.matches.isEmpty()) {
            Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
        }
        val byDay = state.matches.groupBy { localDate(it.startedAt) }
        LazyColumn(Modifier.fillMaxSize().testTag("history_list")) {
            for ((day, matches) in byDay) {
                item(key = "day-$day") {
                    Text(
                        formatDate(day),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(matches, key = { it.id }) { match ->
                    MatchRow(
                        match = match,
                        players = state.players,
                        selecting = selecting,
                        selected = match.id in selection,
                        modifier = Modifier.combinedClickable(
                            onClick = { if (selecting) vm.toggleSelection(match.id) else onOpenMatch(match.id) },
                            onLongClick = { vm.toggleSelection(match.id) },
                        ),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (assigning) {
        AssignPlayersPicker(
            initial = Lineup(),
            players = state.activePlayers,
            vm = vm,
            onDone = { lineup ->
                vm.assign(selection, lineup)
                vm.clearSelection()
                assigning = false
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.delete_matches_confirm, selection.size)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(selection); confirmDelete = false }, modifier = Modifier.testTag("confirm_delete")) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Lineup picker with its own working copy; [onDone] gets the result. */
@Composable
fun AssignPlayersPicker(initial: Lineup, players: List<Player>, vm: RecordsViewModel, onDone: (Lineup) -> Unit) {
    var lineup by rememberSaveable(stateSaver = LineupSaver) { mutableStateOf(initial) }
    LineupPicker(
        lineup = lineup,
        players = players,
        initialTeam = Team.BLUE,
        onToggle = { team, id -> lineup = lineup.toggle(team, id) },
        onAddPlayer = { name, team, onResult ->
            vm.addPlayer(name) { result ->
                when (result) {
                    is AddPlayerResult.Added -> lineup = lineup.toggle(team, result.player.id)
                    is AddPlayerResult.Revived -> lineup = lineup.toggle(team, result.player.id)
                    is AddPlayerResult.Rejected -> Unit
                }
                onResult(result)
            }
        },
        onDone = { onDone(lineup) },
    )
}

private val LineupSaver = androidx.compose.runtime.saveable.Saver<Lineup, String>(
    save = { "${it.blue.joinToString(",")}|${it.red.joinToString(",")}" },
    restore = { s ->
        val (b, r) = s.split('|')
        Lineup(b.split(',').mapNotNull { it.toLongOrNull() }, r.split(',').mapNotNull { it.toLongOrNull() })
    },
)

@Composable
private fun MatchRow(match: MatchRecord, players: Map<Long, Player>, selecting: Boolean, selected: Boolean, modifier: Modifier) {
    fun of(team: Team) = match.lineup.of(team).map { players[it] ?: Player(it, "?", true) }
    val winner = match.winner
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("match_${match.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (selecting) Checkbox(checked = selected, onCheckedChange = null)
        Text(formatTime(match.startedAt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(64.dp))
        TeamLabel(Team.BLUE, of(Team.BLUE), winner == Team.BLUE, Modifier.weight(1f))
        Text(stringResource(R.string.vs), style = MaterialTheme.typography.bodyMedium)
        TeamLabel(Team.RED, of(Team.RED), winner == Team.RED, Modifier.weight(1f))
        Text(
            match.sets.joinToString("  ") { "${it.bluePoints}:${it.redPoints}" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (winner == null) Text(stringResource(R.string.draw), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun TeamLabel(team: Team, players: List<Player>, winner: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(14.dp).background(team.color, CircleShape))
        Text(
            teamNames(players),
            fontSize = 17.sp,
            fontWeight = if (winner) FontWeight.Bold else FontWeight.Normal,
            color = if (winner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (winner) Text("🏆", fontSize = 15.sp)
    }
}
