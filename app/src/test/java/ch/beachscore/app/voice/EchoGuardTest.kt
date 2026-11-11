package ch.beachscore.app.voice

import ch.beachscore.app.voice.EchoGate.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EchoGuardTest {

    private var now = 50_000L
    private val guard = EchoGuard(clock = { now }, tailMs = 500)

    @Test
    fun `not muted without speech`() {
        assertFalse(guard.isMuted())
    }

    @Test
    fun `muted from utterance request until the tail after it is done`() {
        guard.utteranceStarted("a")
        assertTrue(guard.isMuted())
        now += 3000
        assertTrue(guard.isMuted(), "still speaking")
        guard.utteranceFinished("a")
        assertTrue(guard.isMuted(), "tail")
        now += 499
        assertTrue(guard.isMuted())
        now += 1
        assertFalse(guard.isMuted())
    }

    @Test
    fun `a flushed utterance does not end the guard of the one replacing it`() {
        guard.utteranceStarted("a")
        guard.utteranceStarted("b")
        guard.utteranceFinished("a") // QUEUE_FLUSH stopped "a"; "b" is still playing
        now += 2000
        assertTrue(guard.isMuted())
        guard.utteranceFinished("b")
        now += 500
        assertFalse(guard.isMuted())
    }

    @Test
    fun `gate feeds audio, discards results while muted and resets once when the guard ends`() {
        val gate = EchoGate(guard)
        assertEquals(Action.FEED, gate.beforeBuffer())
        assertFalse(gate.discardResults())

        guard.utteranceStarted("a")
        assertEquals(Action.FEED, gate.beforeBuffer(), "audio keeps flowing so endpointing works")
        assertTrue(gate.discardResults())

        guard.utteranceFinished("a")
        now += 200
        assertEquals(Action.FEED, gate.beforeBuffer())
        assertTrue(gate.discardResults(), "tail")

        now += 300
        assertEquals(Action.RESET_THEN_FEED, gate.beforeBuffer(), "half-heard announcement is dropped")
        assertFalse(gate.discardResults())
        assertEquals(Action.FEED, gate.beforeBuffer(), "reset happens only once")
    }

    @Test
    fun `results produced in the buffer that ends the guard are not discarded`() {
        val gate = EchoGate(guard)
        guard.utteranceStarted("a")
        gate.beforeBuffer()
        guard.utteranceFinished("a")
        now += 600
        assertEquals(Action.RESET_THEN_FEED, gate.beforeBuffer())
        assertFalse(gate.discardResults())
    }

    @Test
    fun `gate state follows the guard for the mic indicator`() {
        val gate = EchoGate(guard)
        assertFalse(gate.muted)
        guard.utteranceStarted("a")
        gate.beforeBuffer()
        assertTrue(gate.muted)
    }
}
