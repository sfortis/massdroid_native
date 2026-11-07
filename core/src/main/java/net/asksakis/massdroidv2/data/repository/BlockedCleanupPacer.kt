package net.asksakis.massdroidv2.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Paces the blocked-artist queue cleanup: at most one run per [cooldownMs] for a queue, and a
 * request that lands inside the cooldown is not dropped but runs once when the cooldown ends.
 *
 * Dropping it lost the cleanup of a queue a play had just replaced. The play's own forced run
 * (from `setQueueFilterMode`) read the queue it was replacing, the queue events of the new one
 * arrived inside the cooldown and were thrown away, and a blocked track right after the one
 * that started was heard before the auto-skip caught it.
 */
internal class BlockedCleanupPacer(
    /** Where the trailing runs wait. Injected so a test can drive them with virtual time. */
    private val scope: CoroutineScope,
    private val cooldownMs: Long,
    private val now: () -> Long,
    /** Runs the cleanup; it checks that [queueId] is still the one to clean. */
    private val run: (queueId: String, reason: String) -> Unit,
) {
    private val lastRunAt = ConcurrentHashMap<String, Long>()
    private val trailing = ConcurrentHashMap<String, Job>()

    fun request(queueId: String, reason: String, force: Boolean = false) {
        val at = now()
        val last = lastRunAt[queueId]
        if (force || last == null || at - last >= cooldownMs) {
            lastRunAt[queueId] = at
            run(queueId, reason)
            return
        }
        // One trailing run covers every request made while it waits. Checked and set in one
        // step, so two requests racing from the event collectors cannot start two.
        trailing.compute(queueId) { _, waiting ->
            waiting?.takeIf { it.isActive } ?: scope.launch {
                delay(cooldownMs - (at - last))
                request(queueId, "$reason/trailing", force = true)
            }
        }
    }

    /** Forget a queue that is gone, with any run still waiting for it. */
    fun forget(queueId: String) {
        lastRunAt.remove(queueId)
        trailing.remove(queueId)?.cancel()
    }

    /** Forget every queue, on a disconnect or a new session. */
    fun reset() {
        lastRunAt.clear()
        trailing.values.forEach { it.cancel() }
        trailing.clear()
    }
}
