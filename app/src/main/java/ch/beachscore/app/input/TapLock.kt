package ch.beachscore.app.input

/**
 * Time-based gate for scoring commands: after an accepted point every further point is
 * rejected until [durationMs] has passed, whichever side or input source it comes from.
 *
 * [clock] returns monotonic milliseconds (`SystemClock.elapsedRealtime` in the app, a fake in
 * tests). Not thread-safe: the view model calls it from the main thread only.
 */
class TapLock(
    private val clock: () -> Long,
    durationMs: Long = DEFAULT_MS,
) {
    var durationMs: Long = durationMs
        set(value) {
            require(value in MIN_MS..MAX_MS) { "lock duration out of range: $value" }
            field = value
        }

    /** Clock time at which the current lock ends; in the past when unlocked. */
    var lockedUntilMs: Long = Long.MIN_VALUE
        private set

    init {
        this.durationMs = durationMs
    }

    fun isLocked(): Boolean = clock() < lockedUntilMs

    /** Accepts the command and starts a new lock, or returns false while locked. */
    fun tryAcquire(): Boolean {
        val now = clock()
        if (now < lockedUntilMs) return false
        lockedUntilMs = now + durationMs
        return true
    }

    /** Ends the lock at once (used when the point it guards was undone). */
    fun release() {
        lockedUntilMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_MS = 1500L
        const val MIN_MS = 500L
        const val MAX_MS = 3000L
        const val STEP_MS = 250L
    }
}
