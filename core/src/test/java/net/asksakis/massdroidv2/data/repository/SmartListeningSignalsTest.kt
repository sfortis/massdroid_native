package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import net.asksakis.massdroidv2.data.database.PlayOrigin
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

    // --- generatedListenScale: a mix serving a track is exposure, not choice ---

    @Test
    fun `a passive full listen from a mix is worth half an organic one`() {
        val full = repo.scaleListenSignal(listenedMs = 175_000, durationSec = dur)
        assertThat(repo.generatedListenScale(PlayOrigin.SMART_MIX, full)).isEqualTo(0.5)
        assertThat(repo.generatedListenScale(PlayOrigin.GENRE_RADIO, full)).isEqualTo(0.5)
    }

    @Test
    fun `a partial listen from a mix is barely evidence`() {
        val partial = repo.scaleListenSignal(listenedMs = 100_000, durationSec = dur)
        assertThat(partial).isGreaterThan(0.0)
        assertThat(repo.generatedListenScale(PlayOrigin.SMART_MIX, partial)).isEqualTo(0.15)
    }

    @Test
    fun `bailing out of a mix track keeps its full negative strength`() {
        // Rejecting what the mix chose is real information about the mix.
        val bail = repo.scaleListenSignal(listenedMs = 10_000, durationSec = dur)
        assertThat(bail).isLessThan(0.0)
        assertThat(repo.generatedListenScale(PlayOrigin.SMART_MIX, bail)).isEqualTo(1.0)
    }

    @Test
    fun `organic and unknown listens are never discounted`() {
        // Unknown deliberately keeps full strength: most of it is the listener's
        // own queue resumed after a process restart.
        val full = repo.scaleListenSignal(listenedMs = 175_000, durationSec = dur)
        assertThat(repo.generatedListenScale(PlayOrigin.ORGANIC, full)).isEqualTo(1.0)
        assertThat(repo.generatedListenScale(PlayOrigin.UNKNOWN, full)).isEqualTo(1.0)
    }
}
