package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.SuppressedTrackRow
import net.asksakis.massdroidv2.data.database.TrackScoreRow
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.TRACK_SUPPRESSION_THRESHOLD
import net.asksakis.massdroidv2.domain.recommendation.trackIdentityKey
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * Pins how the repository writes and reads the time-faded track score: a signal
 * is added to the FADED value and stamped now, and suppression is decided on the
 * faded value or the dislike mark, never on the stored score alone.
 *
 * Either mistake reproduces the 2026-10-05 finding, where a score that never
 * forgot kept 11% of a real library out of mixes.
 */
class TrackScoreWriteTest {

    private val transactions = object : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
    }

    private val dao = mockk<PlayHistoryDao>(relaxed = true)

    private val settings = mockk<SettingsRepository>(relaxed = true).also {
        every { it.smartListeningEnabled } returns flowOf(true)
    }

    private val repo = SmartListeningRepositoryImpl(dao, settings, transactions) { emptyList() }

    private val track = Track(
        itemId = "t1",
        provider = "library",
        uri = "library://track/1",
        name = "Some Song",
        artistNames = "Some Artist",
        artistUri = "library://artist/9",
        artistItemId = "9",
        duration = 180.0,
    )
    private val artists = listOf("library://artist/9" to "Some Artist")

    @Test
    fun `a skip is added to the faded score and stamped now`() = runTest {
        // +0.28 written 60 days ago counts 0.14 now; a hard skip (-0.60, under
        // 15 s) lands at -0.46. The old `score = score + delta` gave -0.32.
        val sixtyDaysAgo = System.currentTimeMillis() - 60 * MILLIS_PER_DAY
        coEvery { dao.getTrackScoreState("library://track/1") } returns TrackScoreRow(0.28, sixtyDaysAgo)
        val before = System.currentTimeMillis()

        repo.recordSkip(track, artists, listenedMs = 5_000)

        val score = slot<Double>()
        val updatedAt = slot<Long>()
        coVerify { dao.updateTrackScore("library://track/1", capture(score), capture(updatedAt)) }
        assertThat(score.captured).isWithin(CLOCK_TOLERANCE).of(-0.46)
        assertThat(updatedAt.captured).isAtLeast(before)
    }

    @Test
    fun `the first signal of a new track is the signal itself`() = runTest {
        // A freshly inserted row has score 0 and time 0; zero fades to zero.
        coEvery { dao.getTrackScoreState(any()) } returns TrackScoreRow(0.0, 0L)

        repo.recordSkip(track, artists, listenedMs = 5_000)

        coVerify { dao.updateTrackScore("library://track/1", -0.6, any()) }
    }

    @Test
    fun `suppression is decided on the faded score and the dislike mark`() = runTest {
        val now = System.currentTimeMillis()
        coEvery { dao.getSuppressionCandidates(TRACK_SUPPRESSION_THRESHOLD) } returns listOf(
            // Two hard skips today: -1.20, below the line.
            row("library://track/fresh", "Fresh", score = -1.2, updatedAt = now),
            // The same -1.20 written 120 days ago counts -0.30: back in mixes.
            row("library://track/old", "Old", score = -1.2, updatedAt = now - 120 * MILLIS_PER_DAY),
            // "Not for me" ten years ago, score since pushed positive: still out.
            row(
                "library://track/disliked", "Disliked", score = 0.5,
                updatedAt = now, dislikedAt = now - 3650 * MILLIS_PER_DAY
            ),
        )

        assertThat(repo.getSuppressedTrackUris())
            .containsExactly("library://track/fresh", "library://track/disliked")
    }

    @Test
    fun `identity keys follow the same decision`() = runTest {
        val now = System.currentTimeMillis()
        coEvery { dao.getSuppressionCandidates(any()) } returns listOf(
            row("library://track/fresh", "Fresh", score = -1.2, updatedAt = now),
            row("library://track/old", "Old", score = -1.2, updatedAt = now - 120 * MILLIS_PER_DAY),
            // No title, so no identity: matched on the uri alone.
            row("library://track/untitled", "", score = -1.2, updatedAt = now),
        )

        assertThat(repo.getSuppressedTrackKeys()).containsExactly(trackIdentityKey("Some Artist", "Fresh"))
        assertThat(repo.getSuppressedTrackUris())
            .containsExactly("library://track/fresh", "library://track/untitled")
    }

    private fun row(
        uri: String,
        name: String,
        score: Double,
        updatedAt: Long,
        dislikedAt: Long? = null,
    ) = SuppressedTrackRow(
        uri = uri,
        trackName = name,
        artistName = "Some Artist",
        score = score,
        scoreUpdatedAt = updatedAt,
        dislikedAt = dislikedAt,
    )

    private companion object {
        /** The repository reads its own clock a few milliseconds after the test. */
        const val CLOCK_TOLERANCE = 1e-6
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
