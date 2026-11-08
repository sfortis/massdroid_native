package net.asksakis.massdroidv2.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the resume an interruption owed for a short time after the focus gain, while the
 * output still reads the phone speaker and an external sink is connected.
 *
 * The output stays bound to the device it last played on until the engine reopens it, and the
 * reopen waits for the device list to stay quiet first. So a gain that arrives just before the
 * car route returns reads the speaker, and judging the resume at that moment dropped it while
 * the car was connected. The window resumes as soon as the output reaches an external sink
 * ([onSinkReturned]). When [windowMs] passes it asks [sinkIsBack] once more, in case no route
 * callback reported the return, and otherwise gives the resume up through [onExpired].
 * [cancel] closes it without either: a pause, another loss, or a play that made the resume moot.
 */
internal class SinkReturnWindow(
    private val scope: CoroutineScope,
    private val sinkIsBack: () -> Boolean,
    private val onResume: () -> Unit,
    private val onExpired: () -> Unit,
    private val windowMs: Long = WINDOW_MS,
) {
    private var pending: Job? = null

    val isOpen: Boolean
        @Synchronized get() = pending != null

    /** The focus came back while the output reads the speaker. An open window starts again. */
    @Synchronized
    fun open() {
        pending?.cancel()
        pending = scope.launch {
            delay(windowMs)
            // A job past its delay can no longer be cancelled, so it checks under the lock that
            // nothing closed the window in the meantime.
            val self = coroutineContext[Job]
            val due = synchronized(this@SinkReturnWindow) {
                (pending === self).also { if (it) pending = null }
            }
            if (!due) return@launch
            if (sinkIsBack()) onResume() else onExpired()
        }
    }

    /** The output reached an external sink. Resumes when the window is open; false otherwise. */
    fun onSinkReturned(): Boolean {
        val wasOpen = synchronized(this) {
            val job = pending ?: return false
            job.cancel()
            pending = null
            true
        }
        if (wasOpen) onResume()
        return wasOpen
    }

    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        // In the September logs of issue #68 (a Pixel in Android Auto) each message moved the
        // output off the car and back within 2 to 3 s. By the time the focus comes back most of
        // that has passed, and what is left is the engine's reopen, which waits 350 ms of quiet
        // in the device list, plus the Bluetooth SCO teardown before A2DP returns. Two seconds
        // covers both. The window is kept short because a resume that lands long after the
        // interruption ended no longer reads as the end of that interruption.
        const val WINDOW_MS = 2_000L
    }
}
