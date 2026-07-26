package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Regression lock for the reconnect retry budget.
 *
 * A field battery drain traced back to this: the stable-connection check read
 * `now - lastAuthenticatedAtMs` without ever clearing the marker on disconnect, so
 * once a connection had lived past the threshold, EVERY later failure also measured
 * as "stable" and reset the budget. `attempt` stayed pinned at 1 (the aggressive
 * ~1 s delay) and the budget never exhausted, so an unreachable server produced a
 * ~1 retry/s storm for hours. On cellular each attempt woke the mobile radio: the
 * app's own battery attribution was 92% mobile_radio, with 942 DNS failures and
 * ~790 reconnect attempts logged in a single 4-hour window.
 *
 * The reset must therefore fire at most once per authenticated connection.
 */
class MaWebSocketReconnectBudgetTest {

    private companion object {
        const val T0 = 1_000_000L
        const val STABLE_MS = 30_000L
    }

    private fun client() = MaWebSocketClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

    @Test
    fun `stable connection resets the budget`() {
        val client = client()
        client.lastAuthenticatedAtMs = T0
        client.reconnectAttempts = 7

        client.consumeStableConnectionBackoffReset(T0 + STABLE_MS)

        assertThat(client.reconnectAttempts).isEqualTo(0)
    }

    @Test
    fun `repeated failures after one stable connection do not keep resetting the budget`() {
        val client = client()
        client.lastAuthenticatedAtMs = T0
        client.reconnectAttempts = 7

        // First drop of a stable connection: fresh budget.
        client.consumeStableConnectionBackoffReset(T0 + STABLE_MS)
        assertThat(client.reconnectAttempts).isEqualTo(0)

        // Every subsequent failed attempt must escalate instead of resetting, even
        // though wall-clock keeps growing past the stable threshold.
        client.reconnectAttempts = 12
        client.consumeStableConnectionBackoffReset(T0 + 10 * STABLE_MS)
        assertThat(client.reconnectAttempts).isEqualTo(12)

        client.reconnectAttempts = 41
        client.consumeStableConnectionBackoffReset(T0 + 100 * STABLE_MS)
        assertThat(client.reconnectAttempts).isEqualTo(41)
    }

    @Test
    fun `short lived connection does not reset the budget`() {
        val client = client()
        client.lastAuthenticatedAtMs = T0
        client.reconnectAttempts = 9

        client.consumeStableConnectionBackoffReset(T0 + STABLE_MS - 1)

        assertThat(client.reconnectAttempts).isEqualTo(9)
    }

    @Test
    fun `never authenticated connection does not reset the budget`() {
        val client = client()
        client.reconnectAttempts = 12

        client.consumeStableConnectionBackoffReset(T0)

        assertThat(client.reconnectAttempts).isEqualTo(12)
    }

    @Test
    fun `retry cadence escalates aggressive then patient then idle`() {
        val client = client()

        // Aggressive: ~1 s for the first 30 attempts.
        assertThat(client.retryDelayBaseMs(1)).isEqualTo(1_000L)
        assertThat(client.retryDelayBaseMs(30)).isEqualTo(1_000L)

        // Patient: ~5 s up to the budget.
        assertThat(client.retryDelayBaseMs(31)).isEqualTo(5_000L)
        assertThat(client.retryDelayBaseMs(60)).isEqualTo(5_000L)

        // Idle: past the budget we keep probing slowly instead of giving up, because a
        // route-only outage produces no network callback to revive us.
        assertThat(client.retryDelayBaseMs(61)).isEqualTo(15 * 60_000L)
        assertThat(client.retryDelayBaseMs(5_000)).isEqualTo(15 * 60_000L)
    }

    @Test
    fun `cadence is monotonically non-decreasing across the whole budget`() {
        val client = client()
        var previous = 0L
        for (attempt in 1..70) {
            val delay = client.retryDelayBaseMs(attempt)
            assertThat(delay).isAtLeast(previous)
            previous = delay
        }
    }

    @Test
    fun `marker is cleared even when the connection was too short to reset`() {
        val client = client()
        client.lastAuthenticatedAtMs = T0
        client.reconnectAttempts = 3

        // Short drop: no reset, but the marker is spent...
        client.consumeStableConnectionBackoffReset(T0 + 1_000)
        assertThat(client.lastAuthenticatedAtMs).isEqualTo(0L)

        // ...so a later failure can't retroactively qualify as stable.
        client.consumeStableConnectionBackoffReset(T0 + 5 * STABLE_MS)
        assertThat(client.reconnectAttempts).isEqualTo(3)
    }
}
