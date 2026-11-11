package ch.beachscore.app.ui

import ch.beachscore.app.data.Settings
import ch.beachscore.app.score.Team
import ch.beachscore.app.speech.SpeechAvailability

data class TeamUi(val team: Team, val points: Int, val sets: Int)

data class SetWonUi(val winner: Team, val winnerPoints: Int, val loserPoints: Int)

/** The most recent accepted point; [seq] changes on every point so the UI can re-run its flash. */
data class PointFlash(val team: Team, val seq: Int)

data class ScoreboardUiState(
    val loaded: Boolean = false,
    val left: TeamUi = TeamUi(Team.BLUE, 0, 0),
    val right: TeamUi = TeamUi(Team.RED, 0, 0),
    val canUndo: Boolean = false,
    val setWon: SetWonUi? = null,
    /** True while the tap lock ignores further points. */
    val locked: Boolean = false,
    val lastPoint: PointFlash? = null,
    val settings: Settings = Settings(),
    val speech: SpeechAvailability = SpeechAvailability.UNKNOWN,
)
