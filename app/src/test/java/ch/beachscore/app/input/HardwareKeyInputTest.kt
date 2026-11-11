package ch.beachscore.app.input

import android.view.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareKeyInputTest {

    private val input = HardwareKeyInput()

    private fun collecting(block: suspend (MutableList<ScoreCommand>) -> Unit) = runTest {
        val received = mutableListOf<ScoreCommand>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { input.commands.toList(received) }
        block(received)
        job.cancel()
    }

    @Test
    fun `disabled by default and keys pass through`() = collecting { received ->
        assertFalse(input.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `volume up scores left and volume down scores right`() = collecting { received ->
        input.enabled = true
        assertTrue(input.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0))
        assertTrue(input.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, 0))
        assertTrue(input.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0))
        assertEquals(listOf(ScoreCommand.Point(Side.LEFT), ScoreCommand.Point(Side.RIGHT)), received)
    }

    @Test
    fun `key repeat is consumed but ignored`() = collecting { received ->
        input.enabled = true
        input.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0)
        assertTrue(input.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 1))
        assertTrue(input.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 2))
        assertEquals(listOf(ScoreCommand.Point(Side.LEFT)), received)
    }

    @Test
    fun `other keys are not handled`() = collecting { received ->
        input.enabled = true
        assertFalse(input.onKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, 0))
        assertTrue(received.isEmpty())
    }
}
