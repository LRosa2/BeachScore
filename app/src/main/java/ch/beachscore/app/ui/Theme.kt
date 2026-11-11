package ch.beachscore.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import ch.beachscore.app.R
import ch.beachscore.app.score.Team

/**
 * Deep, saturated team colours with pure white digits: contrast ≥ 7:1 (blue) and ≈ 6.5:1 (red),
 * which stays readable in direct sunlight where pastel colours wash out.
 */
object TeamColors {
    val Blue = Color(0xFF0B3D91)
    val Red = Color(0xFFB3141C)
    val OnTeam = Color.White
}

val Team.color: Color get() = if (this == Team.BLUE) TeamColors.Blue else TeamColors.Red

val Team.nameRes: Int get() = if (this == Team.BLUE) R.string.team_blue else R.string.team_red

@Composable
@ReadOnlyComposable
fun teamName(team: Team): String = stringResource(team.nameRes)

@Composable
fun BeachScoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
