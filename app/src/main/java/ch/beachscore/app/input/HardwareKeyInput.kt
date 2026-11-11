package ch.beachscore.app.input

import android.view.KeyEvent

/**
 * Volume keys as score buttons (what cheap Bluetooth shutter remotes and media clickers send):
 * volume up → left side, volume down → right side. Only while [enabled]; otherwise the keys
 * keep their normal meaning. Key repeat is swallowed so a held button scores once; the tap
 * lock in the view model takes care of the rest.
 */
class HardwareKeyInput : CallbackScoreInput() {

    @Volatile
    var enabled: Boolean = false

    /** Returns true when the key was consumed. Call for every key down and up. */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (!enabled) return false
        val side = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Side.LEFT
            KeyEvent.KEYCODE_VOLUME_DOWN -> Side.RIGHT
            else -> return false
        }
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) emit(ScoreCommand.Point(side))
        return true
    }
}
