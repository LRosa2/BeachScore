package ch.beachscore.app.voice

/**
 * Knows when the app's own TTS is (or was just) speaking, so the microphone does not turn our
 * announcement into a command. Fed by the TTS utterance callbacks; muted from the moment an
 * utterance is requested until [tailMs] after the latest one is done.
 *
 * Only the latest utterance counts: with QUEUE_FLUSH an announcement replaces the previous
 * one, whose "stopped" callback must not end the guard early. Thread-safe.
 */
class EchoGuard(private val clock: () -> Long, private val tailMs: Long = DEFAULT_TAIL_MS) {

    private var activeId: String? = null
    private var mutedUntil = Long.MIN_VALUE

    @Synchronized
    fun utteranceStarted(id: String) {
        activeId = id
    }

    @Synchronized
    fun utteranceFinished(id: String) {
        if (id != activeId) return
        activeId = null
        mutedUntil = clock() + tailMs
    }

    @Synchronized
    fun isMuted(): Boolean = activeId != null || clock() < mutedUntil

    companion object {
        const val DEFAULT_TAIL_MS = 500L
    }
}

/**
 * Per-buffer decision for the audio loop. Audio keeps flowing to the recogniser while muted
 * (so its endpointing stays in step), but results that come out are discarded; when the guard
 * ends the recogniser is reset once, so a half-heard announcement cannot complete into a
 * command with the next words.
 */
class EchoGate(private val guard: EchoGuard) {
    enum class Action { FEED, RESET_THEN_FEED }

    /** Guard state as of the last [beforeBuffer]; drives the "muted" mic indicator. */
    var muted = false
        private set

    fun beforeBuffer(): Action {
        val nowMuted = guard.isMuted()
        val endedNow = muted && !nowMuted
        muted = nowMuted
        return if (endedNow) Action.RESET_THEN_FEED else Action.FEED
    }

    /** Whether a result from the buffer just fed must be dropped. */
    fun discardResults(): Boolean = muted
}
