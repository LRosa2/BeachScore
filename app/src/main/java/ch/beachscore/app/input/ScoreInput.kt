package ch.beachscore.app.input

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Screen side. Inputs speak in sides; the view model maps a side to a team. */
enum class Side { LEFT, RIGHT }

sealed interface ScoreCommand {
    data class Point(val side: Side) : ScoreCommand
    data object Undo : ScoreCommand
}

/**
 * A source of scoring commands. Phase 1: [TouchInput] and [HardwareKeyInput]. Phase 2 adds a
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
