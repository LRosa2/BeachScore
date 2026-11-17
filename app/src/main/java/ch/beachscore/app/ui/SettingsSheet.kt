package ch.beachscore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.BuildConfig
import ch.beachscore.app.R
import ch.beachscore.app.data.Settings
import ch.beachscore.app.input.RemoteAction
import ch.beachscore.app.input.TapLock
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.MatchState
import ch.beachscore.app.speech.SpeechAvailability
import ch.beachscore.app.voice.MicRow
import ch.beachscore.app.voice.Microphones
import ch.beachscore.app.voice.HalfJoiner
import ch.beachscore.app.voice.VoiceSensitivity
import ch.beachscore.app.voice.CommandWords
import ch.beachscore.app.voice.CustomWords
import ch.beachscore.app.voice.VoiceStatus
import ch.beachscore.app.voice.VoiceWords
import ch.beachscore.app.voice.WordField
import ch.beachscore.app.voice.WordProblem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Entries of the menu list at the top of the side panel. */
enum class MenuEntry(val label: Int, val tag: String) {
    SETTINGS(R.string.settings, "menu_settings"),
    HISTORY(R.string.history, "menu_history"),
    PLAYERS(R.string.players, "menu_players"),
    STATISTICS(R.string.statistics, "menu_statistics"),
    ABOUT(R.string.about, "menu_about"),
}

/**
 * The menu as a side panel (landscape). At the top a short list: Settings · History · Players ·
 * Statistics · About. Settings (selected when the panel opens) and About show below the list;
 * the others open their screens through [onOpen]. Settings: points per set · announce ·
 * language · tap lock · remote buttons · voice · test voice. Drawn in-window (not a dialog)
 * so immersive mode stays on.
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
    wordsSave: WordsSave = WordsSave.Idle,
    onSaveWords: (Language, Map<WordField, String>) -> Unit = { _, _ -> },
    onWordsEdited: () -> Unit = {},
    onOpen: (MenuEntry) -> Unit = {},
    learning: RemoteAction? = null,
    onLearn: (RemoteAction) -> Unit = {},
    onCancelLearn: () -> Unit = {},
    onClearRemote: (RemoteAction) -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(MenuEntry.SETTINGS) }
    val scroll = rememberScrollState()
    // In landscape the list fills most of the panel: bring About's lines into view.
    LaunchedEffect(section) { if (section == MenuEntry.ABOUT) scroll.animateScrollTo(scroll.maxValue) }
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
                Modifier.verticalScroll(scroll).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.menu), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose, modifier = Modifier.testTag("settings_close")) { Text(stringResource(R.string.close)) }
                }
                MenuList(section) { entry ->
                    when (entry) {
                        MenuEntry.SETTINGS, MenuEntry.ABOUT -> section = entry
                        else -> onOpen(entry)
                    }
                }
                HorizontalDivider()
                if (section == MenuEntry.ABOUT) {
                    About(settings.language)
                    return@Column
                }
                Text(stringResource(R.string.settings), fontSize = 22.sp, fontWeight = FontWeight.Bold)

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

                RemoteSection(settings, learning, onLearn, onCancelLearn, onClearRemote)

                VoiceSection(settings, voice, voicePermissionDenied, onVoiceToggle, onChange)
                if (settings.voiceCommands) VoiceWordsEditor(settings, wordsSave, onSaveWords, onWordsEdited)

                OutlinedButton(onClick = onTestVoice, modifier = Modifier.testTag("test_voice")) { Text(stringResource(R.string.test_voice)) }
            }
        }
    }
}

@Composable
private fun Labeled(label: Int, vararg args: Any, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label, *args), style = MaterialTheme.typography.titleMedium)
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

        MicrophoneChoice(settings, voice, onChange)

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
                        // No check mark: three labels must fit the narrow panel on one line.
                        icon = {},
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(stringResource(label), maxLines = 1, softWrap = false, fontSize = 14.sp) }
                }
            }
        }
        Text(stringResource(R.string.voice_sensitivity_hint), style = MaterialTheme.typography.bodySmall)
        Labeled(R.string.voice_pause) {
            val choices = HalfJoiner.PAUSE_CHOICES_MS
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { i, ms ->
                    SegmentedButton(
                        selected = settings.voicePauseMs == ms,
                        onClick = { onChange { it.copy(voicePauseMs = ms) } },
                        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                        modifier = Modifier.testTag("pause_${ms / 1000}"),
                        icon = {},
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(stringResource(R.string.voice_pause_seconds, ms / 1000), maxLines = 1, softWrap = false, fontSize = 14.sp) }
                }
            }
        }
        Text(stringResource(R.string.voice_pause_hint), style = MaterialTheme.typography.bodySmall)
        SwitchRow(R.string.show_heard, settings.showHeard, "show_heard") { on -> onChange { it.copy(showHeard = on) } }
    }
}

/** "Microphone": Automatic plus each connected input, and the one actually in use. */
@Composable
private fun MicrophoneChoice(settings: Settings, voice: VoiceUi, onChange: ((Settings) -> Settings) -> Unit) {
    Labeled(R.string.microphone) {
        Column(Modifier.selectableGroup().testTag("microphone")) {
            Microphones.rows(voice.microphones, settings.microphone).forEach { row -> MicrophoneRow(row, onChange) }
        }
        val inUse = voice.activeMicrophone?.let { micLabel(it.kind, it.productName) } ?: "–"
        Text(
            stringResource(R.string.microphone_in_use, inUse),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("microphone_in_use"),
        )
        Text(stringResource(R.string.microphone_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MicrophoneRow(row: MicRow, onChange: ((Settings) -> Settings) -> Unit) {
    val choice = row.choice
    val name = if (choice == null) stringResource(R.string.microphone_automatic) else micLabel(choice.kind, choice.productName)
    val label = if (row.connected) name else stringResource(R.string.microphone_not_connected, name)
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = row.selected, role = Role.RadioButton) { onChange { it.copy(microphone = choice) } }
            .testTag(if (choice == null) "mic_auto" else "mic_${choice.kind}_${choice.productName}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = row.selected, onClick = null, modifier = Modifier.padding(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * "Voice words" of the current app language: Blue team, Red team, Point. Empty = defaults
 * (shown as the placeholder). "Save" stores all three after the checks; "Reset" empties one
 * field and saves the same way.
 */
@Composable
private fun VoiceWordsEditor(
    settings: Settings,
    save: WordsSave,
    onSave: (Language, Map<WordField, String>) -> Unit,
    onEdited: () -> Unit,
) {
    val language = settings.language
    val stored = settings.voiceWords[language] ?: CustomWords()
    val texts = remember(language, stored) {
        mutableStateMapOf<WordField, String>().apply { WordField.entries.forEach { put(it, VoiceWords.fieldText(language, stored, it)) } }
    }
    val defaults = CommandWords.defaultsOf(language)
    val languageName = stringResource(if (language == Language.DE) R.string.language_de else R.string.language_en)
    val fields = listOf(
        WordField.BLUE to R.string.voice_words_blue,
        WordField.RED to R.string.voice_words_red,
        WordField.POINT to R.string.voice_words_point,
    )
    Labeled(R.string.voice_words, languageName) {
        fields.forEach { (field, label) ->
            val name = stringResource(label)
            val resetDescription = stringResource(R.string.voice_words_reset_description, name)
            OutlinedTextField(
                value = texts[field].orEmpty(),
                onValueChange = { texts[field] = it; onEdited() },
                label = { Text(name) },
                supportingText = {
                    Text(
                        stringResource(
                            if (field == WordField.POINT) R.string.voice_words_default_point else R.string.voice_words_default,
                            VoiceWords.display(defaults[field]),
                        ),
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            // Back to the default: an empty team field, the default words in the point field.
                            texts[field] = VoiceWords.fieldText(language, CustomWords(), field)
                            onSave(language, texts.toMap())
                        },
                        enabled = save != WordsSave.Checking,
                        modifier = Modifier.testTag("words_reset_${field.name.lowercase()}"),
                    ) { Icon(StripIcons.Undo, contentDescription = resetDescription) }
                },
                singleLine = true,
                enabled = save != WordsSave.Checking,
                modifier = Modifier.fillMaxWidth().testTag("words_${field.name.lowercase()}"),
            )
        }
        Button(
            onClick = { onSave(language, texts.toMap()) },
            enabled = save != WordsSave.Checking,
            modifier = Modifier.testTag("words_save"),
        ) { Text(stringResource(R.string.voice_words_save)) }
        val message = when (save) {
            WordsSave.Idle -> null
            WordsSave.Checking -> stringResource(R.string.voice_words_checking)
            WordsSave.Saved -> stringResource(R.string.voice_words_saved)
            is WordsSave.Failed -> when (val problem = save.problem) {
                is WordProblem.Unknown -> stringResource(R.string.voice_words_unknown, problem.word)
                is WordProblem.Duplicate -> stringResource(R.string.voice_words_duplicate, problem.word)
                is WordProblem.UndoWord -> stringResource(R.string.voice_words_undo, problem.word)
                is WordProblem.NotOneWord -> stringResource(R.string.voice_words_not_one_word, problem.entry)
                WordProblem.ModelUnavailable -> stringResource(R.string.voice_words_model_unavailable)
            }
        }
        if (message != null) {
            Text(
                message,
                color = if (save is WordsSave.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("words_message"),
            )
        }
        Text(stringResource(R.string.voice_words_hint, CommandWords.undoWord(language)), style = MaterialTheme.typography.bodySmall)
    }
}

/** The short list at the top of the menu; [selected] is highlighted. Rows are 48 dp high. */
@Composable
private fun MenuList(selected: MenuEntry, onClick: (MenuEntry) -> Unit) {
    Column(Modifier.selectableGroup()) {
        MenuEntry.entries.forEach { entry ->
            val isSelected = entry == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .selectable(selected = isSelected, role = Role.Tab) { onClick(entry) }
                    .padding(horizontal = 12.dp)
                    .testTag(entry.tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(entry.label),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** About: app name, author, version and release date, both from the build. */
@Composable
private fun About(language: Language) {
    val date = runCatching {
        LocalDate.parse(BuildConfig.RELEASE_DATE)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(language.tag)))
    }.getOrDefault(BuildConfig.RELEASE_DATE)
    val color = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("about")) {
        Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
        Text(stringResource(R.string.about_author), style = MaterialTheme.typography.titleMedium, color = color)
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge, color = color)
        Text(stringResource(R.string.about_released, date), style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
