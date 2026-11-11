package ch.beachscore.app.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TapLockTest {

    private var now = 10_000L
    private val lock = TapLock(clock = { now }, durationMs = 1500)

    @Test
    fun `first command is accepted`() {
        assertFalse(lock.isLocked())
        assertTrue(lock.tryAcquire())
        assertTrue(lock.isLocked())
    }

    @Test
    fun `commands during the lock are rejected`() {
        assertTrue(lock.tryAcquire())
        now += 1
        assertFalse(lock.tryAcquire())
        now += 1498
        assertFalse(lock.tryAcquire(), "1499 ms after the point is still locked")
    }

    @Test
    fun `lock ends after the duration`() {
        assertTrue(lock.tryAcquire())
        now += 1500
        assertFalse(lock.isLocked())
        assertTrue(lock.tryAcquire())
    }

    @Test
    fun `rejected commands do not extend the lock`() {
        assertTrue(lock.tryAcquire())
        now += 1000
        assertFalse(lock.tryAcquire())
        now += 500
        assertTrue(lock.tryAcquire(), "lock is measured from the accepted point only")
    }

    @Test
    fun `duration can change and applies to the next point`() {
        lock.durationMs = 500
        assertTrue(lock.tryAcquire())
        now += 500
        assertTrue(lock.tryAcquire())
        assertEquals(now + 500, lock.lockedUntilMs)
    }

    @Test
    fun `release unlocks immediately`() {
        assertTrue(lock.tryAcquire())
        lock.release()
        assertTrue(lock.tryAcquire())
    }

    @Test
    fun `duration is limited to the settings range`() {
        assertThrows<IllegalArgumentException> { lock.durationMs = 499 }
        assertThrows<IllegalArgumentException> { lock.durationMs = 3001 }
    }
}
