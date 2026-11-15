package ch.beachscore.app.input

import ch.beachscore.app.score.Team
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Screen side. Inputs speak in sides; the view model maps a side to a team. */
enum class Side { LEFT, RIGHT }

sealed interface ScoreCommand {
    /** A point for the team shown on [side] (touch). */
    data class Point(val side: Side) : ScoreCommand

    /** A point for a named team (voice, remote buttons), independent of Swap sides. */
    data class TeamPoint(val team: Team) : ScoreCommand

    data object Undo : ScoreCommand
}

/**
 * A source of scoring commands. [TouchInput], [RemoteKeyInput] and (phase 2) a
 * voice input implementing this interface; every source goes through the same tap lock.
 */
interface ScoreInput {
    val commands: Flow<ScoreCommand>
}

/** Base for inputs that push commands from callbacks. */
abstract class CallbackScoreInput : ScoreInput {
    private val flow = MutableSharedFlow<ScoreCommand>(extraBufferCapacity = 16)
    final override val commands: Flow<ScoreCommand> = flow.asSharedFlow()

    protected fun emit(command: ScoreCommand) {
        flow.tryEmit(command)
    }
}

/** Taps from the scoreboard UI. */
class TouchInput : CallbackScoreInput() {
    fun point(side: Side) = emit(ScoreCommand.Point(side))
    fun undo() = emit(ScoreCommand.Undo)
}
