package ch.beachscore.app.input

import android.view.KeyEvent
import ch.beachscore.app.score.Team
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteKeyInputTest {

    private val input = RemoteKeyInput().apply {
        keys = RemoteKeys()
            .assign(RemoteAction.BLUE_POINT, KeyEvent.KEYCODE_MEDIA_NEXT)
            .assign(RemoteAction.RED_POINT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            .assign(RemoteAction.UNDO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        active = true
    }

    private fun down(code: Int, repeat: Int = 0) = input.onKey(code, KeyEvent.ACTION_DOWN, repeat)
    private fun up(code: Int) = input.onKey(code, KeyEvent.ACTION_UP, 0)

    private fun collecting(block: suspend (MutableList<ScoreCommand>, MutableList<Pair<RemoteAction, Int>>) -> Unit) = runTest {
        val received = mutableListOf<ScoreCommand>()
        val learned = mutableListOf<Pair<RemoteAction, Int>>()
        val a = launch(UnconfinedTestDispatcher(testScheduler)) { input.commands.toList(received) }
        val b = launch(UnconfinedTestDispatcher(testScheduler)) { input.learned.toList(learned) }
        block(received, learned)
        a.cancel()
        b.cancel()
    }

    @Test
    fun `mapped keys send team commands and are consumed`() = collecting { received, _ ->
        assertTrue(down(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(up(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(down(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(up(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(down(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(
            listOf(ScoreCommand.TeamPoint(Team.BLUE), ScoreCommand.TeamPoint(Team.RED), ScoreCommand.Undo),
            received,
        )
    }

    @Test
    fun `unmapped keys pass through`() = collecting { received, _ ->
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(up(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `auto repeat is consumed but ignored`() = collecting { received, _ ->
        down(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertTrue(down(KeyEvent.KEYCODE_MEDIA_NEXT, 1))
        assertTrue(down(KeyEvent.KEYCODE_MEDIA_NEXT, 2))
        up(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals(listOf<ScoreCommand>(ScoreCommand.TeamPoint(Team.BLUE)), received)
    }

    @Test
    fun `inactive - menu or other screen open - keys keep their normal meaning`() = collecting { received, _ ->
        input.active = false
        assertFalse(down(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(up(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `learn mode captures the next key, even while inactive`() = collecting { received, learned ->
        input.active = false
        input.learning = RemoteAction.RED_POINT
        assertTrue(down(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(input.learning, "one key per learn")
        assertTrue(down(KeyEvent.KEYCODE_VOLUME_DOWN, 1), "its repeat is swallowed too")
        assertTrue(up(KeyEvent.KEYCODE_VOLUME_DOWN), "and its key-up")
        assertEquals(listOf(RemoteAction.RED_POINT to KeyEvent.KEYCODE_VOLUME_DOWN), learned)
        assertTrue(received.isEmpty(), "learning never scores")
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_DOWN), "after learning, inactive again")
    }

    @Test
    fun `learn mode lets system keys through and keeps waiting`() = collecting { _, learned ->
        input.learning = RemoteAction.UNDO
        assertFalse(down(KeyEvent.KEYCODE_BACK))
        assertFalse(up(KeyEvent.KEYCODE_BACK))
        assertEquals(RemoteAction.UNDO, input.learning)
        assertTrue(learned.isEmpty())
    }

    @Test
    fun `learning a mapped key does not score it`() = collecting { received, learned ->
        input.learning = RemoteAction.UNDO
        assertTrue(down(KeyEvent.KEYCODE_MEDIA_NEXT))
        up(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertTrue(received.isEmpty())
        assertEquals(listOf(RemoteAction.UNDO to KeyEvent.KEYCODE_MEDIA_NEXT), learned)
    }
}
