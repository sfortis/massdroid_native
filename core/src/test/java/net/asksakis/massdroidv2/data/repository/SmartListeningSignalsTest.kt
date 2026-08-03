package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * Pins the skip/listen signal curves that drive per-artist score deltas. Pure
 * functions; the repo is built with relaxed mocks only to reach the
 * @VisibleForTesting instance methods.
 */
class SmartListeningSignalsTest {

    private val repo = SmartListeningRepositoryImpl(
        dao = mockk(relaxed = true),
        settingsRepository = mockk(relaxed = true),
        transactions = object : net.asksakis.massdroidv2.data.database.TransactionRunner {
            override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
        },
        artistAliases = { emptyList() },
    )

    private val dur = 180.0

    // --- scaleSkipSignal: harsher the earlier the skip ---

    @Test
    fun `scaleSkipSignal defaults to the full signal when timing is unknown`() {
        assertThat(repo.scaleSkipSignal(listenedMs = null, durationSec = dur)).isEqualTo(-0.50)
        assertThat(repo.scaleSkipSignal(listenedMs = 30_000, durationSec = 0.0)).isEqualTo(-0.50)
    }

    @Test
    fun `scaleSkipSignal is harshest for an early skip`() {
        // The boundaries sit at 15s and 30s. They used to be 5s and 15s, which
        // asked the listener to decide faster than anyone does: a real history
        // put 15% of skips under 5s, and they were mostly navigation.
        assertThat(repo.scaleSkipSignal(3_000, dur)).isEqualTo(-0.60)
        assertThat(repo.scaleSkipSignal(14_000, dur)).isEqualTo(-0.60)
        assertThat(repo.scaleSkipSignal(20_000, dur)).isEqualTo(-0.45)
        assertThat(repo.scaleSkipSignal(29_000, dur)).isEqualTo(-0.45)
    }

    @Test
    fun `scaleSkipSignal softens as more of the track is heard`() {
        assertThat(repo.scaleSkipSignal(31_000, dur)).isEqualTo(-0.35)  // ratio < 0.25
        assertThat(repo.scaleSkipSignal(80_000, dur)).isEqualTo(-0.20)  // ratio < 0.50
        assertThat(repo.scaleSkipSignal(120_000, dur)).isEqualTo(-0.08) // ratio < 0.75
        assertThat(repo.scaleSkipSignal(170_000, dur)).isEqualTo(-0.03) // near-complete
    }

    // --- scaleListenSignal: negative for a near-skip, positive once truly heard ---

    @Test
    fun `scaleListenSignal defaults to the listen signal when timing is unknown`() {
        assertThat(repo.scaleListenSignal(listenedMs = null, durationSec = 100.0)).isEqualTo(0.20)
    }

    @Test
    fun `scaleListenSignal maps the listen ratio onto a graded curve`() {
        assertThat(repo.scaleListenSignal(10_000, 100.0)).isEqualTo(-0.20) // ratio < 0.15
        assertThat(repo.scaleListenSignal(20_000, 100.0)).isEqualTo(-0.05) // ratio < 0.30
        assertThat(repo.scaleListenSignal(40_000, 100.0)).isEqualTo(0.08)  // ratio < 0.50
        assertThat(repo.scaleListenSignal(60_000, 100.0)).isEqualTo(0.18)  // ratio < 0.75
        assertThat(repo.scaleListenSignal(90_000, 100.0)).isEqualTo(0.28)  // mostly heard
    }
}
