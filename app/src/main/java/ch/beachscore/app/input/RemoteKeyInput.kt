package ch.beachscore.app.input

import android.view.KeyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Buttons of a Bluetooth remote (or any key) as score buttons (3.4.0). The activity passes
 * every key event to [onKey]; a key in [keys] sends its action while [active] (scoreboard
 * shown, no menu open) and is consumed, so the volume does not change and music does not
 * start. Auto-repeat and the key-up of a consumed key are consumed too; one press is one
 * action. In learn mode ([learning]) the next learnable key goes to [learned] instead.
 * Called on the main thread.
 */
class RemoteKeyInput : CallbackScoreInput() {

    @Volatile
    var keys: RemoteKeys = RemoteKeys()

    @Volatile
    var active: Boolean = false

    /** The action waiting for a key, or null. Cleared by the key that is learned. */
    @Volatile
    var learning: RemoteAction? = null

    private val learnedFlow = MutableSharedFlow<Pair<RemoteAction, Int>>(extraBufferCapacity = 4)
    val learned: Flow<Pair<RemoteAction, Int>> = learnedFlow.asSharedFlow()

    /** Keys whose down was consumed: their repeats and their up are consumed as well. */
    private val held = mutableSetOf<Int>()

    /** Returns true when the key was consumed. */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        when (action) {
            KeyEvent.ACTION_UP -> return held.remove(keyCode) || (active && keys.actionFor(keyCode) != null)
            KeyEvent.ACTION_DOWN -> Unit
            else -> return false
        }
        if (repeatCount > 0 && keyCode in held) return true
        val learn = learning
        if (learn != null) {
            if (!RemoteKeys.isLearnable(keyCode)) return false
            if (repeatCount == 0) {
                learning = null
                learnedFlow.tryEmit(learn to keyCode)
            }
            held += keyCode
            return true
        }
        if (!active) return false
        val mapped = keys.actionFor(keyCode) ?: return false
        held += keyCode
        if (repeatCount == 0) emit(mapped.command)
        return true
    }
}
