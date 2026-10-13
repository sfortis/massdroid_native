package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient.ReviveAction
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient.ReviveBudget
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Pins how much of the retry budget a revive grants.
 *
 * A network change refills the whole budget, because the server may well be reachable again. The
 * end of a call and a player command only hint at it, and they can repeat many times an hour, so
 * they grant one patient cycle and never touch a short retry that is already pending. Refilling the
 * whole budget on every hint would bring back the reconnect storm of
 * [MaWebSocketReconnectBudgetTest] for a server that is really down.
 */
class MaWebSocketReviveBudgetTest {

    private fun client() = MaWebSocketClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

    /** Mirrors the private constants: 30 aggressive + 30 patient, then the idle sentinel. */
    private companion object {
        const val AGGRESSIVE_RETRY_COUNT = 30
        const val IDLE_SENTINEL = 61
    }

    @Test
    fun `a network change out of the idle phase connects with the whole budget`() {
        val action = client().reviveAction(ReviveBudget.FULL, shortRetryPending = false, attempts = IDLE_SENTINEL)

        assertThat(action).isEqualTo(ReviveAction.ConnectNow(0))
    }

    @Test
    fun `a hint out of the idle phase connects with one patient cycle`() {
        val action = client().reviveAction(ReviveBudget.PATIENT_CYCLE, shortRetryPending = false, attempts = IDLE_SENTINEL)

        assertThat(action).isEqualTo(ReviveAction.ConnectNow(AGGRESSIVE_RETRY_COUNT))
    }

    @Test
    fun `a hint leaves a pending short retry and its budget alone`() {
        val action = client().reviveAction(ReviveBudget.PATIENT_CYCLE, shortRetryPending = true, attempts = 45)

        assertThat(action).isEqualTo(ReviveAction.KeepPendingRetry(45))
    }

    @Test
    fun `a network change keeps a pending short retry but restarts its budget`() {
        val action = client().reviveAction(ReviveBudget.FULL, shortRetryPending = true, attempts = 45)

        assertThat(action).isEqualTo(ReviveAction.KeepPendingRetry(0))
    }

    @Test
    fun `repeated hints cannot keep the retries in the fast phase`() {
        val client = client()
        var attempts = IDLE_SENTINEL
        repeat(10) {
            attempts = client.reviveAction(ReviveBudget.PATIENT_CYCLE, shortRetryPending = false, attempts = attempts).attempts
            // The connect that follows fails and schedules the next retry from here.
            assertThat(client.retryDelayBaseMs(attempts + 1)).isAtLeast(5_000L)
        }
    }
}
