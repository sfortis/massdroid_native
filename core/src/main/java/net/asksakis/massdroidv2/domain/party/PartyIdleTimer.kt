package net.asksakis.massdroidv2.domain.party

import kotlinx.coroutines.delay

/**
 * Tells when the Party screen has gone [timeoutMs] without a touch, so the screen can close
 * itself and let the display sleep again.
 *
 * A touch only records the time, because touches arrive with every pointer event. The waiting
 * side sleeps until the recorded deadline and checks again, so a touch during the wait moves
 * the deadline without restarting anything. [now] is a monotonic clock in milliseconds.
 */
class PartyIdleTimer(
    private val timeoutMs: Long = IDLE_DISMISS_MS,
    private val now: () -> Long
) {
    @Volatile
    private var lastTouchAt = now()

    fun touch() {
        lastTouchAt = now()
    }

    /** Milliseconds left before the timeout, or 0 once it has passed. */
    fun remainingMs(): Long = (lastTouchAt + timeoutMs - now()).coerceAtLeast(0L)

    /**
     * Starts the countdown again and returns once [timeoutMs] have passed without a touch. The
     * caller cancels it when the screen stops being in front.
     */
    suspend fun awaitIdle() {
        touch()
        while (true) {
            val remaining = remainingMs()
            if (remaining == 0L) return
            delay(remaining)
        }
    }

    companion object {
        /** The owner's choice on 2026-11-07: the Party screen closes after a minute untouched. */
        const val IDLE_DISMISS_MS = 60_000L
    }
}
