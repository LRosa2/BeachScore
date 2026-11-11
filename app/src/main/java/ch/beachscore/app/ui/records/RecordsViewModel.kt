package ch.beachscore.app.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.beachscore.app.data.AddPlayerResult
import ch.beachscore.app.data.HistoryStore
import ch.beachscore.app.data.PlayerStore
import ch.beachscore.app.history.CsvWriter
import ch.beachscore.app.history.HeadToHeadRow
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.MatchRecord
import ch.beachscore.app.history.NameCheck
import ch.beachscore.app.history.Period
import ch.beachscore.app.history.Player
import ch.beachscore.app.history.StatSort
import ch.beachscore.app.history.Statistics
import ch.beachscore.app.history.StatisticsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

data class RecordsOptions(
    val period: Period = Period.ALL_TIME,
    val sort: StatSort = StatSort.WIN_RATE,
    /** Head-to-head subject: one player id or a pair; null until chosen. */
    val subject: Set<Long>? = null,
    /** History multi-select. */
    val selection: Set<Long> = emptySet(),
)

data class RecordsUiState(
    val loaded: Boolean = false,
    /** Newest first. */
    val matches: List<MatchRecord> = emptyList(),
    val players: Map<Long, Player> = emptyMap(),
    val options: RecordsOptions = RecordsOptions(),
    val statistics: Statistics = Statistics(emptyList(), emptyList(), 0, 0),
    val headToHead: List<HeadToHeadRow> = emptyList(),
) {
    val activePlayers: List<Player> get() = players.values.filterNot { it.archived }.sortedBy { it.name.lowercase() }
}

/** History, players and statistics screens share this view model (same data, one source). */
class RecordsViewModel(
    private val history: HistoryStore,
    private val playerStore: PlayerStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {

    private val options = MutableStateFlow(RecordsOptions())

    val state: StateFlow<RecordsUiState> = combine(history.matches, playerStore.players, options) { matches, players, opts ->
        val byId = players.associateBy { it.id }
        val inPeriod = StatisticsCalculator.inPeriod(matches, opts.period, now(), zone())
        val stats = StatisticsCalculator.calculate(matches, byId, opts.period, now(), zone())
        RecordsUiState(
            loaded = true,
            matches = matches,
            players = byId,
            options = opts.copy(selection = opts.selection.filterTo(mutableSetOf()) { id -> matches.any { it.id == id } }),
            statistics = stats.copy(
                players = StatisticsCalculator.sorted(stats.players, opts.sort),
                pairs = StatisticsCalculator.sortedPairs(stats.pairs, opts.sort),
            ),
            headToHead = opts.subject?.let { StatisticsCalculator.headToHead(inPeriod, it, byId) }.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordsUiState())

    fun setPeriod(period: Period) = options.update { it.copy(period = period) }
    fun setSort(sort: StatSort) = options.update { it.copy(sort = sort) }
    fun setSubject(subject: Set<Long>?) = options.update { it.copy(subject = subject) }

    fun toggleSelection(id: Long) = options.update { o ->
        o.copy(selection = if (id in o.selection) o.selection - id else o.selection + id)
    }

    fun clearSelection() = options.update { it.copy(selection = emptySet()) }

    fun assign(ids: Collection<Long>, lineup: Lineup) {
        viewModelScope.launch { history.assignLineup(ids.toList(), lineup) }
    }

    fun delete(ids: Collection<Long>) {
        viewModelScope.launch {
            history.delete(ids.toList())
            options.update { it.copy(selection = it.selection - ids.toSet()) }
        }
    }

    fun addPlayer(name: String, onResult: (AddPlayerResult) -> Unit) {
        viewModelScope.launch { onResult(playerStore.add(name)) }
    }

    fun rename(id: Long, name: String, onResult: (NameCheck) -> Unit) {
        viewModelScope.launch { onResult(playerStore.rename(id, name)) }
    }

    fun archive(id: Long) {
        viewModelScope.launch { playerStore.archive(id) }
    }

    /** CSV of the whole history, or of [ids] when given (multi-select). */
    fun csv(ids: Set<Long>? = null): String {
        val s = state.value
        val matches = if (ids.isNullOrEmpty()) s.matches else s.matches.filter { it.id in ids }
        return CsvWriter.write(matches, s.players, zone())
    }
}
