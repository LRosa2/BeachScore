package ch.beachscore.app.ui

import ch.beachscore.app.data.Settings
import ch.beachscore.app.history.Lineup
import ch.beachscore.app.history.Player
import ch.beachscore.app.input.RemoteAction
import ch.beachscore.app.score.Team
import ch.beachscore.app.speech.SpeechAvailability
import ch.beachscore.app.voice.Heard
import ch.beachscore.app.voice.Verdict
import ch.beachscore.app.voice.InputDevice
import ch.beachscore.app.voice.VoiceStatus
import ch.beachscore.app.voice.WordProblem

data class TeamUi(val team: Team, val points: Int, val sets: Int, val players: List<Player> = emptyList())

data class SetWonUi(val winner: Team, val winnerPoints: Int, val loserPoints: Int)

/** The most recent accepted point; [seq] changes on every point so the UI can re-run its flash. */
data class PointFlash(val team: Team, val seq: Int)

/** The last remote button press and what came of it; [seq] changes with every press. */
data class RemotePress(val action: RemoteAction, val verdict: Verdict, val seq: Int)

/** Result of the last "Save" of the voice words. */
sealed interface WordsSave {
    data object Idle : WordsSave
    /** Loading the speech model to check the words. */
    data object Checking : WordsSave
    data object Saved : WordsSave
    data class Failed(val problem: WordProblem) : WordsSave
}

data class ScoreboardUiState(
    val loaded: Boolean = false,
    val left: TeamUi = TeamUi(Team.BLUE, 0, 0),
    val right: TeamUi = TeamUi(Team.RED, 0, 0),
    val canUndo: Boolean = false,
    val lineup: Lineup = Lineup(),
    /** Players offered by the lineup picker (archived ones are hidden). */
    val activePlayers: List<Player> = emptyList(),
    val setWon: SetWonUi? = null,
    /** True while the tap lock ignores further points. */
    val locked: Boolean = false,
    val lastPoint: PointFlash? = null,
    val settings: Settings = Settings(),
    val speech: SpeechAvailability = SpeechAvailability.UNKNOWN,
    val voice: VoiceUi = VoiceUi(),
    /** Voice commands could not be (or stay) on because the microphone permission is missing. */
    val voicePermissionDenied: Boolean = false,
    val wordsSave: WordsSave = WordsSave.Idle,
    /** "Remote buttons": the action waiting for a key press, or null. */
    val learning: RemoteAction? = null,
    /** Short confirmation of the last remote press (null when faded). */
    val remotePress: RemotePress? = null,
)

/**
 * Voice diagnostics: [status] drives the mic indicator; [lastHeard] is the fading "what was
 * heard" line (null when hidden or switched off). [heardSeq] changes with every new line.
 */
data class VoiceUi(
    val status: VoiceStatus = VoiceStatus.Off,
    val lastHeard: Heard? = null,
    val heardSeq: Int = 0,
    /** Connected inputs, for the "Microphone" setting. */
    val microphones: List<InputDevice> = emptyList(),
    /** The input actually in use while listening (shown in settings and the indicator). */
    val activeMicrophone: InputDevice? = null,
)
