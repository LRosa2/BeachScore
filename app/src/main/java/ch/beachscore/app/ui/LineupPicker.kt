package ch.beachscore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.R
import ch.beachscore.app.data.AddPlayerResult
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.NameCheck
import ch.beachscore.app.history.Player
import ch.beachscore.app.history.PlayerNames
import ch.beachscore.app.score.Team

/**
 * Picks up to two players per team, for the scoreboard and for history editing. One panel for
 * both teams: the selected team tab decides where a tapped player goes; tapping a player who is
 * on that team removes them, a player from the other team moves over. Large chips for sandy
 * fingers. Stateless: the caller owns the [lineup].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineupPicker(
    lineup: Lineup,
    players: List<Player>,
    initialTeam: Team,
    onToggle: (Team, Long) -> Unit,
    onAddPlayer: (name: String, team: Team, onResult: (AddPlayerResult) -> Unit) -> Unit,
    onDone: () -> Unit,
) {
    var team by rememberSaveable { mutableStateOf(initialTeam) }
    val byId = remember(players) { players.associateBy { it.id } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone)
            .testTag("lineup_picker"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .widthIn(max = 900.dp)
                .padding(vertical = 16.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (t in Team.entries) {
                        TeamTab(
                            team = t,
                            names = lineup.of(t).mapNotNull { byId[it]?.name ?: "?" },
                            selected = t == team,
                            onClick = { team = t },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (players.isEmpty()) {
                    Text(stringResource(R.string.no_players_yet), style = MaterialTheme.typography.bodyLarge)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in players) {
                        val on = Team.entries.firstOrNull { p.id in lineup.of(it) }
                        val enabled = on == team || lineup.of(team).size < Lineup.MAX_PER_TEAM
                        PlayerChip(p.name, on, enabled) { onToggle(team, p.id) }
                    }
                }
                AddPlayerRow(onAdd = { name, onResult -> onAddPlayer(name, team, onResult) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDone, modifier = Modifier.heightIn(min = 52.dp).testTag("lineup_done")) {
                        Text(stringResource(R.string.done), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamTab(team: Team, names: List<String>, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(team.color.copy(alpha = if (selected) 1f else 0.35f), RoundedCornerShape(14.dp))
            .border(if (selected) 3.dp else 0.dp, Color.White, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("tab_${team.name.lowercase()}"),
    ) {
        Text(teamName(team), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            names.joinToString(" · ").ifEmpty { stringResource(R.string.no_players) },
            color = Color.White.copy(alpha = if (names.isEmpty()) 0.7f else 1f),
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayerChip(name: String, on: Team?, enabled: Boolean, onClick: () -> Unit) {
    val background = on?.color ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(background, RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp)
            .testTag("chip_$name"),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, fontSize = 20.sp, color = if (on != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

/** Text field + button; shows why a name was refused. Shared with the Players screen. */
@Composable
fun AddPlayerRow(onAdd: (name: String, onResult: (AddPlayerResult) -> Unit) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<NameCheck?>(null) }
    val submit = {
        onAdd(text) { result ->
            if (result is AddPlayerResult.Rejected) error = result.reason else {
                text = ""
                error = null
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text(stringResource(R.string.player_name)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(nameError(it)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).testTag("add_player_name"),
        )
        OutlinedButton(onClick = submit, modifier = Modifier.heightIn(min = 52.dp).testTag("add_player")) {
            Text(stringResource(R.string.add_player))
        }
    }
}

@Composable
fun nameError(check: NameCheck): String = when (check) {
    NameCheck.Empty -> stringResource(R.string.name_empty)
    NameCheck.TooLong -> stringResource(R.string.name_too_long, PlayerNames.MAX_LENGTH)
    is NameCheck.Duplicate -> stringResource(R.string.name_duplicate, check.existing.name)
    is NameCheck.Ok -> ""
}
