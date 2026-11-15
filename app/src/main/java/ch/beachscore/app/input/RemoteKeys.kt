package ch.beachscore.app.input

import android.view.KeyEvent
import ch.beachscore.app.score.Team

/** What a remote button can do (3.4.0). Follows the team, not the court side. */
enum class RemoteAction(val command: ScoreCommand) {
    BLUE_POINT(ScoreCommand.TeamPoint(Team.BLUE)),
    RED_POINT(ScoreCommand.TeamPoint(Team.RED)),
    UNDO(ScoreCommand.Undo),
}

/** Keys with a translated name in the settings; the rest show as a letter or "Key 87". */
enum class KnownKey(val code: Int) {
    VOLUME_UP(KeyEvent.KEYCODE_VOLUME_UP),
    VOLUME_DOWN(KeyEvent.KEYCODE_VOLUME_DOWN),
    VOLUME_MUTE(KeyEvent.KEYCODE_VOLUME_MUTE),
    PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    PLAY(KeyEvent.KEYCODE_MEDIA_PLAY),
    PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE),
    NEXT(KeyEvent.KEYCODE_MEDIA_NEXT),
    PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
    STOP(KeyEvent.KEYCODE_MEDIA_STOP),
    FAST_FORWARD(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
    REWIND(KeyEvent.KEYCODE_MEDIA_REWIND),
    HEADSET(KeyEvent.KEYCODE_HEADSETHOOK),
    CAMERA(KeyEvent.KEYCODE_CAMERA),
    ENTER(KeyEvent.KEYCODE_ENTER),
    SPACE(KeyEvent.KEYCODE_SPACE),
    UP(KeyEvent.KEYCODE_DPAD_UP),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
    CENTER(KeyEvent.KEYCODE_DPAD_CENTER),
    PAGE_UP(KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN(KeyEvent.KEYCODE_PAGE_DOWN),
}

sealed interface KeyName {
    data class Known(val key: KnownKey) : KeyName
    /** A letter or digit, shown as is. */
    data class Text(val text: String) : KeyName
    /** Anything else: "Key 87". */
    data class Code(val code: Int) : KeyName
}

/**
 * The learned remote buttons: key code → action, in the order they were learned. Immutable;
 * stored as "87=BLUE_POINT,88=RED_POINT". Pure (key codes are compile-time constants).
 */
data class RemoteKeys(private val bindings: List<Pair<Int, RemoteAction>> = emptyList()) {

    val isEmpty: Boolean get() = bindings.isEmpty()

    fun actionFor(keyCode: Int): RemoteAction? = bindings.firstOrNull { it.first == keyCode }?.second

    fun keysFor(action: RemoteAction): List<Int> = bindings.filter { it.second == action }.map { it.first }

    /** Learns [keyCode] for [action]; it leaves any other action. The oldest of too many goes. */
    fun assign(action: RemoteAction, keyCode: Int): RemoteKeys {
        if (!isLearnable(keyCode)) return this
        var list = bindings.filter { it.first != keyCode }
        val mine = list.filter { it.second == action }
        if (mine.size >= MAX_PER_ACTION) list = list - mine.first()
        return RemoteKeys(list + (keyCode to action))
    }

    fun clear(action: RemoteAction): RemoteKeys = RemoteKeys(bindings.filter { it.second != action })

    fun encode(): String = bindings.joinToString(",") { (code, action) -> "$code=${action.name}" }

    companion object {
        /** "At least two" so two remotes can both work; three leaves room for a spare. */
        const val MAX_PER_ACTION = 3

        /** Keys the system needs (or never gives to apps); they are never learned or consumed. */
        private val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SOFT_SLEEP, KeyEvent.KEYCODE_BRIGHTNESS_UP,
            KeyEvent.KEYCODE_BRIGHTNESS_DOWN, KeyEvent.KEYCODE_ALL_APPS, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_SYSRQ,
        )

        fun isLearnable(keyCode: Int): Boolean = keyCode > 0 && keyCode !in SYSTEM_KEYS

        fun decode(text: String?): RemoteKeys {
            var keys = RemoteKeys()
            for (part in text.orEmpty().split(',')) {
                val (code, action) = part.split('=').takeIf { it.size == 2 } ?: continue
                val keyCode = code.trim().toIntOrNull() ?: continue
                val a = RemoteAction.entries.firstOrNull { it.name == action.trim() } ?: continue
                keys = keys.assign(a, keyCode)
            }
            return keys
        }

        /**
         * Migration from "Hardware buttons score points" (volume up = left, volume down =
         * right): the teams shown on those sides now get the keys, and keep them after a swap.
         */
        fun fromVolumeKeys(blueOnLeft: Boolean): RemoteKeys {
            val left = if (blueOnLeft) RemoteAction.BLUE_POINT else RemoteAction.RED_POINT
            val right = if (blueOnLeft) RemoteAction.RED_POINT else RemoteAction.BLUE_POINT
            return RemoteKeys().assign(left, KeyEvent.KEYCODE_VOLUME_UP).assign(right, KeyEvent.KEYCODE_VOLUME_DOWN)
        }

        fun nameOf(keyCode: Int): KeyName {
            KnownKey.entries.firstOrNull { it.code == keyCode }?.let { return KeyName.Known(it) }
            return when (keyCode) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> KeyName.Text(('A' + (keyCode - KeyEvent.KEYCODE_A)).toString())
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> KeyName.Text(('0' + (keyCode - KeyEvent.KEYCODE_0)).toString())
                else -> KeyName.Code(keyCode)
            }
        }
    }
}
