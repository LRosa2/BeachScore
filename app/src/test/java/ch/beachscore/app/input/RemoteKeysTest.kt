package ch.beachscore.app.input

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteKeysTest {

    private val next = KeyEvent.KEYCODE_MEDIA_NEXT
    private val prev = KeyEvent.KEYCODE_MEDIA_PREVIOUS
    private val play = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    private val volUp = KeyEvent.KEYCODE_VOLUME_UP

    @Test
    fun `assign maps a key to an action`() {
        val keys = RemoteKeys().assign(RemoteAction.BLUE_POINT, next).assign(RemoteAction.UNDO, play)
        assertEquals(RemoteAction.BLUE_POINT, keys.actionFor(next))
        assertEquals(RemoteAction.UNDO, keys.actionFor(play))
        assertNull(keys.actionFor(prev))
        assertEquals(listOf(next), keys.keysFor(RemoteAction.BLUE_POINT))
        assertTrue(keys.keysFor(RemoteAction.RED_POINT).isEmpty())
    }

    @Test
    fun `a key assigned elsewhere moves to the new action`() {
        val keys = RemoteKeys().assign(RemoteAction.BLUE_POINT, next).assign(RemoteAction.RED_POINT, next)
        assertEquals(RemoteAction.RED_POINT, keys.actionFor(next))
        assertTrue(keys.keysFor(RemoteAction.BLUE_POINT).isEmpty())
    }

    @Test
    fun `several keys per action, a fourth replaces the oldest`() {
        val keys = RemoteKeys()
            .assign(RemoteAction.BLUE_POINT, next)
            .assign(RemoteAction.BLUE_POINT, volUp)
            .assign(RemoteAction.BLUE_POINT, KeyEvent.KEYCODE_A)
        assertEquals(listOf(next, volUp, KeyEvent.KEYCODE_A), keys.keysFor(RemoteAction.BLUE_POINT))
        val more = keys.assign(RemoteAction.BLUE_POINT, KeyEvent.KEYCODE_B)
        assertEquals(listOf(volUp, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B), more.keysFor(RemoteAction.BLUE_POINT))
        assertNull(more.actionFor(next))
    }

    @Test
    fun `assigning the same key again keeps one entry`() {
        val keys = RemoteKeys().assign(RemoteAction.UNDO, play).assign(RemoteAction.UNDO, play)
        assertEquals(listOf(play), keys.keysFor(RemoteAction.UNDO))
    }

    @Test
    fun `clear removes only the keys of that action`() {
        val keys = RemoteKeys().assign(RemoteAction.BLUE_POINT, next).assign(RemoteAction.RED_POINT, prev).clear(RemoteAction.BLUE_POINT)
        assertNull(keys.actionFor(next))
        assertEquals(RemoteAction.RED_POINT, keys.actionFor(prev))
    }

    @Test
    fun `system keys cannot be learned`() {
        for (code in listOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SLEEP,
        )) assertFalse(RemoteKeys.isLearnable(code), "key $code")
        for (code in listOf(
            next, prev, play, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            volUp, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE, 300,
        )) assertTrue(RemoteKeys.isLearnable(code), "key $code")
        assertEquals(RemoteKeys(), RemoteKeys().assign(RemoteAction.UNDO, KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `encode and decode round trip, garbage is skipped`() {
        val keys = RemoteKeys().assign(RemoteAction.BLUE_POINT, next).assign(RemoteAction.BLUE_POINT, volUp)
            .assign(RemoteAction.RED_POINT, prev).assign(RemoteAction.UNDO, play)
        assertEquals(keys, RemoteKeys.decode(keys.encode()))
        assertEquals(RemoteKeys(), RemoteKeys.decode(""))
        assertEquals(RemoteKeys(), RemoteKeys.decode(null))
        val partly = RemoteKeys.decode("87=BLUE_POINT,x=RED_POINT,88=NOPE,4=UNDO,,24")
        assertEquals(RemoteKeys().assign(RemoteAction.BLUE_POINT, 87), partly)
    }

    @Test
    fun `migration from the volume key toggle follows the teams on screen`() {
        val normal = RemoteKeys.fromVolumeKeys(blueOnLeft = true)
        assertEquals(RemoteAction.BLUE_POINT, normal.actionFor(volUp))
        assertEquals(RemoteAction.RED_POINT, normal.actionFor(KeyEvent.KEYCODE_VOLUME_DOWN))
        val swapped = RemoteKeys.fromVolumeKeys(blueOnLeft = false)
        assertEquals(RemoteAction.RED_POINT, swapped.actionFor(volUp))
        assertEquals(RemoteAction.BLUE_POINT, swapped.actionFor(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `key names`() {
        assertEquals(KeyName.Known(KnownKey.VOLUME_UP), RemoteKeys.nameOf(volUp))
        assertEquals(KeyName.Known(KnownKey.NEXT), RemoteKeys.nameOf(next))
        assertEquals(KeyName.Known(KnownKey.PLAY_PAUSE), RemoteKeys.nameOf(play))
        assertEquals(KeyName.Text("A"), RemoteKeys.nameOf(KeyEvent.KEYCODE_A))
        assertEquals(KeyName.Text("7"), RemoteKeys.nameOf(KeyEvent.KEYCODE_7))
        assertEquals(KeyName.Code(300), RemoteKeys.nameOf(300))
    }
}
