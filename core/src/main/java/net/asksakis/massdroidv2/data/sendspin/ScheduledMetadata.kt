package net.asksakis.massdroidv2.data.sendspin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The rc1 metadata role's scheduled updates (spec `roles/metadata/v1.md`).
 *
 * A `server/state` whose metadata `timestamp` lies in the future takes effect at that time on
 * the local clock, so the title of the next track appears when it starts to play rather than
 * when its audio is sent ahead. At most one update is pending; any newer one, due now or later,
 * replaces it.
 */
internal class ScheduledMetadata(
    private val scope: CoroutineScope,
    private val publish: (ServerMetadataPayload) -> Unit,
) {
    private var pending: Job? = null

    /** Publishes [metadata] after [delayMs], or at once when it is due. */
    @Synchronized
    fun offer(metadata: ServerMetadataPayload, delayMs: Long) {
        pending?.cancel()
        pending = null
        if (delayMs <= 0L) {
            publish(metadata)
            return
        }
        pending = scope.launch {
            delay(delayMs)
            // A job past its delay can no longer be cancelled, so it checks under the lock that
            // nothing newer replaced or cancelled it in the meantime.
            val self = coroutineContext[Job]
            synchronized(this@ScheduledMetadata) {
                if (pending === self) {
                    pending = null
                    publish(metadata)
                }
            }
        }
    }

    /** Drops the pending update, for a connection that ends. */
    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
