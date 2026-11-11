package ch.beachscore.app.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.beachscore.app.R
import ch.beachscore.app.history.Period
import ch.beachscore.app.history.Player
import ch.beachscore.app.history.Record
import ch.beachscore.app.history.StatSort

/** Per player, per pair and head-to-head, with period filter and sort. */
@Composable
fun StatisticsScreen(state: RecordsUiState, vm: RecordsViewModel, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val opts = state.options
    val stats = state.statistics
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RecordsTopBar(stringResource(R.string.statistics), onBack = onBack)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((period, label) in listOf(
                Period.TODAY to R.string.period_today,
                Period.LAST_30_DAYS to R.string.period_30_days,
                Period.THIS_YEAR to R.string.period_year,
                Period.ALL_TIME to R.string.period_all,
            )) {
                FilterChip(selected = opts.period == period, onClick = { vm.setPeriod(period) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("period_${period.name.lowercase()}"))
            }
            Text("  " + stringResource(R.string.sort_by), style = MaterialTheme.typography.labelLarge)
            for ((sort, label) in listOf(StatSort.WIN_RATE to R.string.sort_win_rate, StatSort.MATCHES to R.string.sort_matches)) {
                FilterChip(selected = opts.sort == sort, onClick = { vm.setSort(sort) }, label = { Text(stringResource(label)) })
            }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.tab_players, R.string.tab_pairs, R.string.tab_head_to_head).forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) }, modifier = Modifier.testTag("stats_tab_$i"))
            }
        }
        if (stats.excluded > 0) {
            Text(
                stringResource(R.string.excluded_note, stats.excluded),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("excluded_note"),
            )
        }
        when (tab) {
            0 -> RecordTable(stats.players.map { listOf(it.player) to it.record })
            1 -> RecordTable(stats.pairs.map { it.players to it.record })
            else -> HeadToHead(state, vm)
        }
    }
}

@Composable
private fun HeadToHead(state: RecordsUiState, vm: RecordsViewModel) {
    val subjects = state.statistics.players.map { listOf(it.player) } + state.statistics.pairs.map { it.players }
    val chosen = state.options.subject
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.h2h_choose), style = MaterialTheme.typography.labelLarge)
            for (s in subjects) {
                val ids = s.map { it.id }.toSet()
                FilterChip(selected = chosen == ids, onClick = { vm.setSubject(ids) }, label = { Text(teamNames(s)) })
            }
        }
        if (chosen != null) RecordTable(state.headToHead.map { it.opponent to it.record }, emptyText = R.string.h2h_empty)
    }
}

@Composable
private fun RecordTable(rows: List<Pair<List<Player>, Record>>, emptyText: Int = R.string.stats_empty) {
    if (rows.isEmpty()) {
        Text(stringResource(emptyText), modifier = Modifier.padding(16.dp))
        return
    }
    val headers = listOf(R.string.col_matches, R.string.col_wins, R.string.col_losses, R.string.col_draws, R.string.col_win_rate, R.string.col_sets, R.string.col_points, R.string.col_diff)
    LazyColumn(Modifier.fillMaxSize().testTag("stats_table")) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.col_name), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                headers.forEach { Cell(stringResource(it), bold = true) }
            }
            HorizontalDivider()
        }
        items(rows) { (players, r) ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(teamNames(players), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Cell(r.matches.toString())
                Cell(r.wins.toString())
                Cell(r.losses.toString())
                Cell(r.draws.toString())
                Cell(percent(r.winRate), bold = true)
                Cell("${r.setsWon}:${r.setsLost}")
                Cell("${r.pointsFor}:${r.pointsAgainst}")
                Cell((if (r.pointDifference > 0) "+" else "") + r.pointDifference)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Cell(text: String, bold: Boolean = false) {
    Text(text, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.width(76.dp))
}
