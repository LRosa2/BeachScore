package ch.beachscore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.R

/**
 * Full-screen "Set Blue 15:12" with one big "Next set" button. Covers both halves, so no point
 * can be scored while it is shown; Undo stays available and takes the winning point back.
 */
@Composable
fun SetWonOverlay(setWon: SetWonUi, onNextSet: () -> Unit, onNewMatch: () -> Unit, onUndo: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(setWon.winner.color.copy(alpha = 0.97f))
            .swallowTouches()
            .testTag("set_won"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                stringResource(R.string.set_won, teamName(setWon.winner), setWon.winnerPoints, setWon.loserPoints),
                color = TeamColors.OnTeam,
                fontSize = 72.sp,
                lineHeight = 80.sp,
                fontWeight = FontWeight.Black,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            // Two equally large buttons: single-set and multi-set matches are both common.
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for ((label, action, tag) in listOf(
                    Triple(R.string.next_set, onNextSet, "next_set"),
                    Triple(R.string.new_match, onNewMatch, "overlay_new_match"),
                )) {
                    Button(
                        onClick = action,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = setWon.winner.color),
                        modifier = Modifier.height(96.dp).width(320.dp).testTag(tag),
                    ) {
                        Text(stringResource(label), fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            TextButton(onClick = onUndo, modifier = Modifier.testTag("overlay_undo")) {
                Text(stringResource(R.string.undo), fontSize = 20.sp, color = TeamColors.OnTeam)
            }
        }
    }
}
