package ch.beachscore.app.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.R
import ch.beachscore.app.history.NameCheck
import ch.beachscore.app.history.Player
import ch.beachscore.app.ui.AddPlayerRow
import ch.beachscore.app.ui.nameError

/** Add, rename and remove (archive) players. */
@Composable
fun PlayersScreen(state: RecordsUiState, vm: RecordsViewModel, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf<Player?>(null) }
    var removing by remember { mutableStateOf<Player?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RecordsTopBar(stringResource(R.string.players), onBack = onBack)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            AddPlayerRow(onAdd = { name, onResult -> vm.addPlayer(name, onResult) })
        }
        if (state.loaded && state.activePlayers.isEmpty()) {
            Text(stringResource(R.string.no_players_yet), modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.activePlayers, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("player_${p.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.name, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { renaming = p }) { Text(stringResource(R.string.rename)) }
                    TextButton(onClick = { removing = p }, modifier = Modifier.testTag("remove_${p.name}")) { Text(stringResource(R.string.remove)) }
                }
                HorizontalDivider()
            }
        }
    }

    renaming?.let { p -> RenameDialog(p, vm, onClose = { renaming = null }) }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            text = { Text(stringResource(R.string.remove_player_confirm, p.name)) },
            confirmButton = {
                TextButton(onClick = { vm.archive(p.id); removing = null }, modifier = Modifier.testTag("confirm_remove")) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RenameDialog(player: Player, vm: RecordsViewModel, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(player.name) }
    var error by remember { mutableStateOf<NameCheck?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(nameError(it)) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.rename(player.id, text) { r -> if (r is NameCheck.Ok) onClose() else error = r } }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
