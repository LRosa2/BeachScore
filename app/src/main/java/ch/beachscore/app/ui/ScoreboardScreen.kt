package ch.beachscore.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ch.beachscore.app.R
import ch.beachscore.app.input.Side
import ch.beachscore.app.score.Team

/** Connects the view model to the stateless screen and hosts the settings sheet. */
@Composable
fun ScoreboardRoute(viewModel: ScoreboardViewModel, state: ScoreboardUiState, onOpenHistory: () -> Unit = {}) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pickerTeam by rememberSaveable { mutableStateOf<Team?>(null) }
    var speechNoticeDismissed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Asked only when the user switches voice commands on; never at start.
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onMicPermissionResult(granted)
    }
    val onVoiceToggle: (Boolean) -> Unit = { on ->
        when {
            !on -> viewModel.updateSettings { it.copy(voiceCommands = false) }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                viewModel.onMicPermissionResult(granted = true)
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ScoreboardScreen(
        state = state,
        onTap = viewModel::onTap,
        onUndo = viewModel::onUndo,
        onSwapSides = viewModel::onSwapSides,
        onNewMatch = viewModel::onNewMatch,
        onNextSet = viewModel::onNextSet,
        onOpenSettings = { showSettings = true },
        onOpenHistory = onOpenHistory,
        onNames = { pickerTeam = it },
    )
    pickerTeam?.let { team ->
        LineupPicker(
            lineup = state.lineup,
            players = state.activePlayers,
            initialTeam = team,
            onToggle = viewModel::togglePlayer,
            onAddPlayer = viewModel::addPlayer,
            onDone = { pickerTeam = null },
        )
    }
    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            speech = state.speech,
            voice = state.voice,
            voicePermissionDenied = state.voicePermissionDenied,
            onVoiceToggle = onVoiceToggle,
            speechNoticeDismissed = speechNoticeDismissed,
            onDismissSpeechNotice = { speechNoticeDismissed = true },
            onChange = viewModel::updateSettings,
            onTestVoice = viewModel::onTestVoice,
            onClose = { showSettings = false },
            wordsSave = state.wordsSave,
            onSaveWords = viewModel::saveVoiceWords,
            onWordsEdited = viewModel::onVoiceWordsEdited,
        )
    }
}

@Composable
fun ScoreboardScreen(
    state: ScoreboardUiState,
    onTap: (Side) -> Unit,
    onUndo: () -> Unit,
    onSwapSides: () -> Unit,
    onNewMatch: () -> Unit,
    onNextSet: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onNames: (Team) -> Unit = {},
) {
    // Left means left on screen, whatever the locale: hardware keys and swap rely on it.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!state.loaded) return@Box
            val currentOnTap by rememberUpdatedState(onTap)
            Row(Modifier.fillMaxSize().pointerInput(Unit) { detectScoringTaps { currentOnTap(it) } }) {
                for ((side, team) in listOf(Side.LEFT to state.left, Side.RIGHT to state.right)) {
                    TeamHalf(
                        team = team,
                        side = side,
                        locked = state.locked,
                        flash = state.lastPoint?.takeIf { it.team == team.team },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            // Name areas: a sibling layer above the halves. Only the pills themselves take touches
            // (the rest of this layer has no pointer input), and a sibling on top receives the
            // touch instead of the halves, so tapping a name never scores.
            Row(Modifier.fillMaxSize()) {
                for ((side, team) in listOf(Side.LEFT to state.left, Side.RIGHT to state.right)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        NameArea(team, side, onClick = { onNames(team.team) }, modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
            }
            LockBar(state, Modifier.align(Alignment.TopCenter))
            VoiceIndicator(state.voice, Modifier.align(Alignment.TopCenter))
            ControlStrip(
                canUndo = state.canUndo,
                onUndo = onUndo,
                onSwapSides = onSwapSides,
                onNewMatch = onNewMatch,
                onOpenSettings = onOpenSettings,
                onOpenHistory = onOpenHistory,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            state.setWon?.let { SetWonOverlay(it, onNextSet = onNextSet, onNewMatch = onNewMatch, onUndo = onUndo) }
        }
    }
}

/**
 * One gesture detector over both halves: the first pointer down scores for the half it lands
 * on; every other pointer is swallowed until all fingers are up. Scoring on down (not up) makes
 * a sandy finger that slides a bit still count exactly once.
 */
private suspend fun PointerInputScope.detectScoringTaps(onSide: (Side) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onSide(if (down.position.x < size.width / 2f) Side.LEFT else Side.RIGHT)
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun TeamHalf(team: TeamUi, side: Side, locked: Boolean, flash: PointFlash?, modifier: Modifier) {
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flash?.seq) {
        if (flash == null) return@LaunchedEffect
        flashAlpha.snapTo(0.55f)
        flashAlpha.animateTo(0f, tween(durationMillis = 450))
    }
    val numberAlpha by animateFloatAsState(if (locked) 0.72f else 1f, label = "lockDim")

    Box(
        modifier
            .background(team.team.color)
            .drawBehind { if (flashAlpha.value > 0f) drawRect(Color.White.copy(alpha = flashAlpha.value)) }
            .testTag("half_$side"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // As large as the half allows: digit glyphs are ~0.71 em high and a tabular digit is
            // ~0.56 em wide, so this keeps two digits inside the width with a small margin.
            val fontSize = with(LocalDensity.current) {
                minOf(maxHeight * 0.95f, maxWidth * 0.78f).toSp()
            }
            Text(
                text = team.points.toString(),
                color = TeamColors.OnTeam,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    fontWeight = FontWeight.Black,
                    // Tabular digits: "9" and "10" do not make the layout jump.
                    fontFeatureSettings = "tnum",
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                modifier = Modifier
                    .wrapContentSize(unbounded = true)
                    .alpha(numberAlpha)
                    .testTag("points_$side"),
            )
        }
    }
}

/**
 * Small pill near the top of a half: the sets indicator and the team's players, or a subtle
 * "Players" hint. Opens the lineup picker. Deliberately small (one line, 44 dp high) so the
 * score area stays the dominant tap target; it never scores.
 */
@Composable
private fun NameArea(team: TeamUi, side: Side, onClick: () -> Unit, modifier: Modifier) {
    val names = team.players.joinToString(" · ") { it.name }
    Row(
        modifier
            .padding(top = 10.dp)
            .fillMaxWidth(0.7f)
            .wrapContentWidth()
            .heightIn(min = 44.dp)
            .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("names_$side"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (team.sets > 0) SetDots(teamName(team.team), team.sets, Modifier)
        Text(
            names.ifEmpty { stringResource(R.string.players_hint) },
            color = TeamColors.OnTeam.copy(alpha = if (names.isEmpty()) 0.6f else 1f),
            fontSize = 20.sp,
            fontWeight = if (names.isEmpty()) FontWeight.Normal else FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SetDots(name: String, sets: Int, modifier: Modifier) {
    val description = stringResource(R.string.sets_won, name, sets)
    Row(
        modifier.height(20.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sets <= 5) {
            repeat(sets) { Box(Modifier.size(18.dp).background(TeamColors.OnTeam, CircleShape)) }
        } else {
            Box(Modifier.size(18.dp).background(TeamColors.OnTeam, CircleShape))
            Text("× $sets", color = TeamColors.OnTeam, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Thin bar across the top that shrinks while the tap lock is active. */
@Composable
private fun LockBar(state: ScoreboardUiState, modifier: Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(state.lastPoint?.seq, state.locked) {
        if (state.locked) {
            progress.snapTo(1f)
            progress.animateTo(0f, tween(state.settings.tapLockMs.toInt(), easing = LinearEasing))
        } else {
            progress.snapTo(0f)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .drawBehind {
                val w = size.width * progress.value
                drawRect(Color.White.copy(alpha = 0.85f), topLeft = Offset((size.width - w) / 2f, 0f), size = Size(w, size.height))
            },
    )
}
