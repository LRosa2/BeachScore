package ch.beachscore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import ch.beachscore.app.input.TapLock
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.speech.SpeechAvailability
import ch.beachscore.app.voice.VoiceSensitivity
import ch.beachscore.app.voice.VoiceStatus
import java.util.Locale

/**
 * Settings as a side panel (landscape): points per set · announce · language · tap lock ·
 * hardware buttons · test voice. Drawn in-window (not a dialog) so immersive mode stays on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    speech: SpeechAvailability,
    voice: VoiceUi,
    voicePermissionDenied: Boolean,
    onVoiceToggle: (Boolean) -> Unit,
    speechNoticeDismissed: Boolean,
    onDismissSpeechNotice: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onTestVoice: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .testTag("settings_sheet"),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.55f)
                // Swallow clicks inside the panel so they do not close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose, modifier = Modifier.testTag("settings_close")) { Text(stringResource(R.string.close)) }
                }

                if (speech == SpeechAvailability.UNAVAILABLE && !speechNoticeDismissed) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.speech_unavailable), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = onDismissSpeechNotice, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.ok)) }
                        }
                    }
                }

                Labeled(R.string.points_per_set) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        MatchState.ALLOWED_TARGETS.forEachIndexed { i, target ->
                            SegmentedButton(
                                selected = settings.pointsPerSet == target,
                                onClick = { onChange { it.copy(pointsPerSet = target) } },
                                shape = SegmentedButtonDefaults.itemShape(i, MatchState.ALLOWED_TARGETS.size),
                                modifier = Modifier.testTag("target_$target"),
                            ) { Text(target.toString()) }
                        }
                    }
                }

                SwitchRow(R.string.announce_score, settings.announce, "announce") { on -> onChange { it.copy(announce = on) } }

                Labeled(R.string.language) {
                    val languages = listOf(Language.DE to R.string.language_de, Language.EN to R.string.language_en)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        languages.forEachIndexed { i, (language, label) ->
                            SegmentedButton(
                                selected = settings.language == language,
                                onClick = { onChange { it.copy(language = language) } },
                                shape = SegmentedButtonDefaults.itemShape(i, languages.size),
                                modifier = Modifier.testTag("language_${language.tag}"),
                            ) { Text(stringResource(label)) }
                        }
                    }
                }

                TapLockSlider(settings.tapLockMs) { ms -> onChange { it.copy(tapLockMs = ms) } }

                Column {
                    SwitchRow(R.string.hardware_buttons, settings.hardwareButtons, "hardware") { on -> onChange { it.copy(hardwareButtons = on) } }
                    Text(stringResource(R.string.hardware_buttons_hint), style = MaterialTheme.typography.bodySmall)
                }

                VoiceSection(settings, voice, voicePermissionDenied, onVoiceToggle, onChange)

                OutlinedButton(onClick = onTestVoice, modifier = Modifier.testTag("test_voice")) { Text(stringResource(R.string.test_voice)) }
            }
        }
    }
}

@Composable
private fun Labeled(label: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun TapLockSlider(valueMs: Long, onChange: (Long) -> Unit) {
    // Local while dragging, saved when released.
    var dragging by remember(valueMs) { mutableFloatStateOf(valueMs.toFloat()) }
    val steps = ((TapLock.MAX_MS - TapLock.MIN_MS) / TapLock.STEP_MS).toInt() - 1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.tap_lock, String.format(Locale.ROOT, "%.2f", dragging / 1000f)),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onChange(dragging.toLong()) },
            valueRange = TapLock.MIN_MS.toFloat()..TapLock.MAX_MS.toFloat(),
            steps = steps,
            modifier = Modifier.testTag("tap_lock"),
        )
        Text(stringResource(R.string.tap_lock_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSection(
    settings: Settings,
    voice: VoiceUi,
    permissionDenied: Boolean,
    onVoiceToggle: (Boolean) -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            SwitchRow(R.string.voice_commands, settings.voiceCommands, "voice", onVoiceToggle)
            Text(stringResource(R.string.voice_commands_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (permissionDenied && !settings.voiceCommands) {
            Text(
                stringResource(R.string.voice_permission_denied),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("voice_permission_denied"),
            )
        }
        if (!settings.voiceCommands) return@Column

        when (val status = voice.status) {
            is VoiceStatus.Preparing -> Text(stringResource(R.string.voice_preparing, (status.progress * 100).toInt()))
            is VoiceStatus.Failed -> Text(
                stringResource(
                    if (status.reason == VoiceStatus.Failed.Reason.MODEL) R.string.voice_failed_model else R.string.voice_failed_microphone,
                ),
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }

        Labeled(R.string.voice_sensitivity) {
            val levels = listOf(
                VoiceSensitivity.STRICT to R.string.voice_sensitivity_strict,
                VoiceSensitivity.NORMAL to R.string.voice_sensitivity_normal,
                VoiceSensitivity.RELAXED to R.string.voice_sensitivity_relaxed,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                levels.forEachIndexed { i, (level, label) ->
                    SegmentedButton(
                        selected = settings.voiceSensitivity == level,
                        onClick = { onChange { it.copy(voiceSensitivity = level) } },
                        shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                        modifier = Modifier.testTag("sensitivity_${level.name.lowercase()}"),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        Text(stringResource(R.string.voice_sensitivity_hint), style = MaterialTheme.typography.bodySmall)
        SwitchRow(R.string.show_heard, settings.showHeard, "show_heard") { on -> onChange { it.copy(showHeard = on) } }
    }
}
