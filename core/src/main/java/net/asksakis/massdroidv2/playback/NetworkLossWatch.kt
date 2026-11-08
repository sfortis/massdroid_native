package net.asksakis.massdroidv2.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells a lost default network that nothing replaced from one that a handover is replacing.
 *
 * When Wi-Fi goes away with no other network, the sockets on it can stay open with nothing
 * behind them until their pings time out, and the Sendspin stream is then noticed as dropped
 * only after its buffer has played out. A Wi-Fi to mobile handover can also report the old
 * network lost a moment before the new one arrives, and that case must not cut a socket that
 * would have survived. So a loss counts only when no default network has appeared after
 * [graceMs]; a network arriving in the meantime cancels it.
 */
internal class NetworkLossWatch(
    private val scope: CoroutineScope,
    private val hasDefaultNetwork: () -> Boolean,
    private val onLossConfirmed: () -> Unit,
    private val graceMs: Long = GRACE_MS,
) {
    private var pending: Job? = null

    /** The default network was lost. A loss already waiting starts its grace again. */
    @Synchronized
    fun onLost() {
        pending?.cancel()
        pending = scope.launch {
            delay(graceMs)
            // A job past its delay can no longer be cancelled, so it checks under the lock that
            // nothing cancelled it in the meantime.
            val self = coroutineContext[Job]
            val due = synchronized(this@NetworkLossWatch) {
                (pending === self).also { if (it) pending = null }
            }
            if (due && !hasDefaultNetwork()) onLossConfirmed()
        }
    }

    /** A default network is available again, or the watch stops. */
    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        // Long enough for the next network of a handover to arrive, short enough that the
        // stream's buffer is still mostly there when the drop path starts.
        const val GRACE_MS = 2_000L
    }
}
