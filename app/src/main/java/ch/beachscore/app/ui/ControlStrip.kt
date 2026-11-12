package ch.beachscore.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val StripColor = Color(0xD9000000)
private const val HOLD_TO_RESET_MS = 1000

/**
 * Small strip at the bottom centre, away from the big tap areas: Undo (icon) · Swap sides
 * (icon) · New match (text, hold ~1 s) · Menu (icon). Touches on the strip never reach the
 * halves. Icons are 48 dp targets; a long press shows their label.
 */
@Composable
fun ControlStrip(
    canUndo: Boolean,
    onUndo: () -> Unit,
    onSwapSides: () -> Unit,
    onNewMatch: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hintToken by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableIntStateOf(0) }
    LaunchedEffect(hintToken) {
        if (hintToken == 0) return@LaunchedEffect
        hintVisible = hintToken
        delay(2000)
        hintVisible = 0
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (hintVisible != 0) {
            Text(
                stringResource(R.string.hold_to_reset),
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .background(StripColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("hold_hint"),
            )
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(StripColor)
                .swallowTouches()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconStripButton(StripIcons.Undo, R.string.undo, onUndo, enabled = canUndo, tag = "undo")
            IconStripButton(StripIcons.Swap, R.string.swap_sides, onSwapSides, tag = "swap")
            HoldToResetButton(onReset = onNewMatch, onShortTap = { hintToken++ })
            IconStripButton(StripIcons.Menu, R.string.menu, onOpenMenu, tag = "menu")
        }
    }
}

/** A 48 dp icon button; the label is its content description and a tooltip on long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconStripButton(icon: ImageVector, label: Int, onClick: () -> Unit, enabled: Boolean = true, tag: String) {
    val text = stringResource(label)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text, fontSize = 16.sp) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag)) {
            Icon(
                icon,
                contentDescription = text,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/** Material Symbols paths (Apache 2.0), drawn here so the big icon library is not needed. */
internal object StripIcons {
    val Undo = icon("undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    val Swap = icon("swap", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z")
    val Menu = icon("menu", "M3,18h18v-2H3v2zm0,-5h18v-2H3v2zm0,-7v2h18V6H3z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.White))
        .build()
}

/** "New match": a long press with a visible progress fill; a short tap only shows a hint. */
@Composable
private fun HoldToResetButton(onReset: () -> Unit, onShortTap: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnReset by rememberUpdatedState(onReset)
    val currentOnShortTap by rememberUpdatedState(onShortTap)
    val label = stringResource(R.string.new_match)

    Box(
        Modifier
            .fillMaxHeight()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.12f))
                drawRect(Color(0xFFFFB300), size = Size(size.width * progress.value, size.height))
            }
            .semantics {
                role = Role.Button
                onLongClick(label) { currentOnReset(); true }
            }
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        var completed = false
                        val fill = launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(HOLD_TO_RESET_MS, easing = LinearEasing))
                            completed = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            currentOnReset()
                        }
                        waitForUpOrCancellation()
                        if (!completed) {
                            fill.cancel()
                            currentOnShortTap()
                        }
                        scope.launch { progress.snapTo(0f) }
                    }
                }
            }
            .padding(horizontal = 14.dp)
            .testTag("new_match"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = Color.White)
    }
}

/**
 * Makes the whole area a hit target, so taps on it (gaps between buttons included) never reach
 * the sibling halves underneath. It deliberately consumes nothing: consuming here would cancel
 * the clicks of the buttons inside.
 */
fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
