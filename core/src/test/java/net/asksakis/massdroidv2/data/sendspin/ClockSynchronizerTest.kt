package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure timing-math tests for [ClockSynchronizer] (the sendspin-js time-filter.ts
 * port). Covers the public conversion API on a deterministically seeded filter
 * state; the Kalman covariance internals are exercised indirectly and are a
 * later @VisibleForTesting tier.
 */
class ClockSynchronizerTest {

    /**
     * Feeds exactly one symmetric time response. The first sample seeds the
     * offset directly to the measurement and leaves drift disabled, so the
     * filter ends in a known, drift-free state with `offset == offsetUs`.
     */
    private fun seededWithOffset(offsetUs: Long): ClockSynchronizer {
        val sync = ClockSynchronizer()
        // measurement = ((serverRecv - clientTx) + (serverTx - clientRecv)) / 2
        // Symmetric server (recv == tx == offsetUs + clientRecv/2) -> measurement = offsetUs.
        val clientTx = 0L
        val clientRecv = 100L
        val serverPivot = offsetUs + clientRecv / 2 // recv==tx so the (serverTx-clientRecv) leg balances
        sync.processTimeResponse(
            clientTransmittedUs = clientTx,
            serverReceivedUs = serverPivot,
            serverTransmittedUs = serverPivot,
            clientReceivedUs = clientRecv,
        )
        return sync
    }

    @Test
    fun `fresh synchronizer is identity before any measurement`() {
        val sync = ClockSynchronizer()
        assertThat(sync.serverToLocalUs(0L)).isEqualTo(0L)
        assertThat(sync.serverToLocalUs(12_345L)).isEqualTo(12_345L)
    }

    @Test
    fun `serverToLocal subtracts the seeded offset when drift disabled`() {
        val sync = seededWithOffset(5_000L)
        // local = server - offset (drift off): 1_000_000 - 5_000
        assertThat(sync.serverToLocalUs(1_000_000L)).isEqualTo(995_000L)
    }

    @Test
    fun `serverToLocal handles a negative offset`() {
        val sync = seededWithOffset(-5_000L)
        assertThat(sync.serverToLocalUs(1_000_000L)).isEqualTo(1_005_000L)
    }

    @Test
    fun `local-server round trip is idempotent at zero drift`() {
        val sync = seededWithOffset(5_000L)
        val server = 4_242_424L
        assertThat(sync.localToServerUs(sync.serverToLocalUs(server))).isEqualTo(server)
        val local = 9_999_999L
        assertThat(sync.serverToLocalUs(sync.localToServerUs(local))).isEqualTo(local)
    }

    @Test
    fun `single measurement marks the filter synced`() {
        val sync = ClockSynchronizer()
        assertThat(sync.isSynced()).isFalse()
        seededWithOffset(5_000L).let { assertThat(it.isSynced()).isTrue() }
    }
}
