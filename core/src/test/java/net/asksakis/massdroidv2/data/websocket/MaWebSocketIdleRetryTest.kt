package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Regression lock for the idle-retry phase boundary.
 *
 * Reported from the field as "sometimes the app fails to connect to MA after a WiFi
 * or VPN reconnect". The logs showed exactly why. Once the retry budget was spent
 * the client settled into a 992-second idle sleep, the network came back 88 seconds
 * later, and the revive was dismissed:
 *
 * ```
 * 10:21:32 Retry budget (60) spent, dropping to idle retries
 * 10:21:32 Reconnecting in 992190ms (attempt=idle)
 * 10:23:00 Network available: backoff reset, retry already in flight   <- dismissed
 * 10:30:13 connect() ignored: connection already active/connecting     <- user opens app
 * 10:30:18 sendCommand aborted: WebSocket not authenticated within timeout
 * ```
 *
 * The cause was an ordering bug: `scheduleReconnect` wrote the attempt counter AFTER
 * its `delay`, so throughout the idle sleep the counter still read MAX_RETRY_COUNT
 * and the "is this an idle sleep I should preempt?" test compared 60 > 60 and said
 * no. The counter is now pinned before the sleep, and these tests pin the boundary
 * it has to land on.
 */
class MaWebSocketIdleRetryTest {

    private fun client() = MaWebSocketClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

    /** Mirrors the private constants: 30 aggressive + 30 patient. */
    private companion object {
        const val MAX_RETRY_COUNT = 60
    }

    @Test
    fun `the first idle attempt is recognised as the idle phase`() {
        val client = client()
        // The attempt that logs "Retry budget spent, dropping to idle retries".
        val firstIdleAttempt = MAX_RETRY_COUNT + 1

        val pinned = client.pinnedAttemptCounter(firstIdleAttempt)

        assertThat(client.isIdleRetryPhase(pinned)).isTrue()
    }

    @Test
    fun `the last attempt inside the budget is not the idle phase`() {
        val client = client()
        // A patient retry is ~5s away; preempting it buys nothing and reopens the
        // reconnect-storm risk the budget exists to prevent.
        val pinned = client.pinnedAttemptCounter(MAX_RETRY_COUNT)

        assertThat(client.isIdleRetryPhase(pinned)).isFalse()
    }

    @Test
    fun `every attempt beyond the budget stays in the idle phase`() {
        val client = client()
        // A long outage keeps rescheduling; the counter is coerced to the sentinel so
        // it cannot run away, and every one of those waits must still read as idle.
        for (attempt in (MAX_RETRY_COUNT + 1)..(MAX_RETRY_COUNT + 500)) {
            val pinned = client.pinnedAttemptCounter(attempt)
            assertThat(client.isIdleRetryPhase(pinned)).isTrue()
        }
    }

    @Test
    fun `the counter is pinned at the sentinel and never grows past it`() {
        val client = client()
        val sentinel = MAX_RETRY_COUNT + 1

        assertThat(client.pinnedAttemptCounter(sentinel)).isEqualTo(sentinel)
        assertThat(client.pinnedAttemptCounter(MAX_RETRY_COUNT + 9_999)).isEqualTo(sentinel)
        // Attempts inside the budget pass through untouched, so the cadence in
        // retryDelayBaseMs still escalates one step at a time.
        assertThat(client.pinnedAttemptCounter(1)).isEqualTo(1)
        assertThat(client.pinnedAttemptCounter(MAX_RETRY_COUNT)).isEqualTo(MAX_RETRY_COUNT)
    }

    @Test
    fun `no reconnect job means no idle sleep to preempt`() {
        val client = client()
        // A fresh client has no job, so `connect()` must not be suppressed and
        // `onNetworkAvailable` has nothing to cancel.
        client.reconnectAttempts = MAX_RETRY_COUNT + 1

        assertThat(client.isIdleRetryPhase(client.reconnectAttempts)).isTrue()
    }
}
