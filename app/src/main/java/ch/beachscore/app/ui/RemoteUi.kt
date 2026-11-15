package ch.beachscore.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.R
import ch.beachscore.app.data.Settings
import ch.beachscore.app.input.KeyName
import ch.beachscore.app.input.KnownKey
import ch.beachscore.app.input.RemoteAction
import ch.beachscore.app.input.RemoteKeys
import ch.beachscore.app.voice.Verdict

/** "Remote buttons": one row per action with its buttons, Assign/Cancel and Clear. */
@Composable
fun RemoteSection(
    settings: Settings,
    learning: RemoteAction?,
    onLearn: (RemoteAction) -> Unit,
    onCancelLearn: () -> Unit,
    onClear: (RemoteAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("remote_section")) {
        Text(stringResource(R.string.remote_buttons), style = MaterialTheme.typography.titleMedium)
        for (action in RemoteAction.entries) {
            val tag = action.tag
            val keys = settings.remoteKeys.keysFor(action)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(actionText(action)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    val detail = when {
                        learning == action -> stringResource(R.string.remote_press_a_button)
                        keys.isEmpty() -> stringResource(R.string.remote_not_assigned)
                        else -> keys.map { keyLabel(it) }.joinToString(", ")
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (learning == action) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("remote_keys_$tag"),
                    )
                }
                if (learning == action) {
                    OutlinedButton(onClick = onCancelLearn, modifier = Modifier.testTag("remote_cancel_$tag")) { Text(stringResource(R.string.cancel)) }
                } else {
                    OutlinedButton(onClick = { onLearn(action) }, modifier = Modifier.testTag("remote_assign_$tag")) { Text(stringResource(R.string.remote_assign)) }
                }
                TextButton(onClick = { onClear(action) }, enabled = keys.isNotEmpty(), modifier = Modifier.testTag("remote_clear_$tag")) {
                    Text(stringResource(R.string.remote_clear))
                }
            }
        }
        Text(stringResource(R.string.remote_buttons_hint), style = MaterialTheme.typography.bodySmall)
    }
}

private val RemoteAction.tag: String get() = when (this) {
    RemoteAction.BLUE_POINT -> "blue"
    RemoteAction.RED_POINT -> "red"
    RemoteAction.UNDO -> "undo"
}

fun actionText(action: RemoteAction): Int = when (action) {
    RemoteAction.BLUE_POINT -> R.string.remote_blue_point
    RemoteAction.RED_POINT -> R.string.remote_red_point
    RemoteAction.UNDO -> R.string.remote_undo
}

@Composable
fun keyLabel(keyCode: Int): String = when (val name = RemoteKeys.nameOf(keyCode)) {
    is KeyName.Known -> stringResource(knownKeyText(name.key))
    is KeyName.Text -> name.text
    is KeyName.Code -> stringResource(R.string.remote_key_code, name.code)
}

private fun knownKeyText(key: KnownKey): Int = when (key) {
    KnownKey.VOLUME_UP -> R.string.key_volume_up
    KnownKey.VOLUME_DOWN -> R.string.key_volume_down
    KnownKey.VOLUME_MUTE -> R.string.key_volume_mute
    KnownKey.PLAY_PAUSE -> R.string.key_play_pause
    KnownKey.PLAY -> R.string.key_play
    KnownKey.PAUSE -> R.string.key_pause
    KnownKey.NEXT -> R.string.key_next
    KnownKey.PREVIOUS -> R.string.key_previous
    KnownKey.STOP -> R.string.key_stop
    KnownKey.FAST_FORWARD -> R.string.key_fast_forward
    KnownKey.REWIND -> R.string.key_rewind
    KnownKey.HEADSET -> R.string.key_headset
    KnownKey.CAMERA -> R.string.key_camera
    KnownKey.ENTER -> R.string.key_enter
    KnownKey.SPACE -> R.string.key_space
    KnownKey.UP -> R.string.key_up
    KnownKey.DOWN -> R.string.key_down
    KnownKey.LEFT -> R.string.key_left
    KnownKey.RIGHT -> R.string.key_right
    KnownKey.CENTER -> R.string.key_center
    KnownKey.PAGE_UP -> R.string.key_page_up
    KnownKey.PAGE_DOWN -> R.string.key_page_down
}

private val Pill = Color(0xB3000000)
private val Accepted = Color(0xFF7CFC8A)
private val Rejected = Color(0xFFFFC14D)

/**
 * "Remote: Blue point ✓" (or "✗ locked") under the voice indicator for a moment after a
 * remote press, so the player can check it from afar. Not a touch target.
 */
@Composable
fun RemoteIndicator(press: RemotePress?, modifier: Modifier = Modifier) {
    AnimatedVisibility(press != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        // Keeps the last press visible while it fades out.
        val shown = remember { mutableStateOf(press) }
        if (press != null) shown.value = press
        val last = shown.value ?: return@AnimatedVisibility
        Row(
            Modifier
                .padding(top = 8.dp)
                .background(Pill, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("remote_press"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.remote_press, stringResource(actionText(last.action))), color = Color.White, fontSize = 18.sp)
            when (val verdict = last.verdict) {
                is Verdict.Accepted -> Text("✓", color = Accepted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                is Verdict.Rejected -> Text("✗ ${stringResource(reasonText(verdict.reason))}", color = Rejected, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
