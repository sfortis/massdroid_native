package net.asksakis.massdroidv2.data.sendspin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long a stream counts as active after its connection dropped.
 *
 * The phone plays through a drop from the audio it holds, up to about 30 s, so the stream is
 * still audible after the socket is gone. Ending it at the drop made the TV clear its media
 * session and notification 5 s later while the music played on. It ends instead when the
 * audio held at the drop has played out, unless the caller cancels the hold because a stream
 * came back or ended first. A reconnect that brings no stream is ended separately, by the
 * manager's idle teardown.
 */
internal class StreamDropHold(
    private val scope: CoroutineScope,
    private val onPlayedOut: () -> Unit,
) {
    private var pending: Job? = null

    /**
     * The connection dropped in the middle of a stream with [playableMs] of audio left. A hold
     * already running keeps its time: the retries of one drop do not refill the buffer.
     */
    @Synchronized
    fun onLoss(playableMs: Long) {
        if (pending != null) return
        pending = scope.launch {
            delay(playableMs.coerceAtLeast(0L) + PLAYED_OUT_GRACE_MS)
            // A job past its delay can no longer be cancelled, so it checks under the lock that
            // nothing cancelled it in the meantime.
            val self = coroutineContext[Job]
            val due = synchronized(this@StreamDropHold) {
                (pending === self).also { if (it) pending = null }
            }
            if (due) onPlayedOut()
        }
    }

    /** A stream came back or ended, or the manager stopped. */
    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        // The audio the decoder and the output still hold beyond what is counted.
        const val PLAYED_OUT_GRACE_MS = 1_000L
    }
}
