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
import net.asksakis.massdroidv2.data.database.PlayOrigin
import net.asksakis.massdroidv2.data.database.SUPPRESSED_ARTIST_SEPARATOR
import net.asksakis.massdroidv2.data.database.SmartFeedbackEntity
import net.asksakis.massdroidv2.data.database.SuppressedTrackRow
import net.asksakis.massdroidv2.data.database.TrackScoreRow
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MIX_EXCLUSION_SCORE
import net.asksakis.massdroidv2.domain.recommendation.MIX_SKIP_EXCLUSION_DAYS
import net.asksakis.massdroidv2.domain.recommendation.trackIdentityKey
import net.asksakis.massdroidv2.domain.repository.ArtistAliasLookup
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

    private val repo = SmartListeningRepositoryImpl(dao, settings, transactions) { ArtistAliasLookup.Unresolvable }

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
    fun `mix exclusion is decided on the faded score, the dislike mark and the last skip`() = runTest {
        val now = System.currentTimeMillis()
        coEvery { dao.getSuppressionCandidates(MIX_EXCLUSION_SCORE, any(), any(), any()) } returns listOf(
            // Two hard skips today: -1.20.
            row("library://track/fresh", "Fresh", score = -1.2, updatedAt = now),
            // The same -1.20 written 120 days ago counts -0.30, still below -0.15.
            row("library://track/old", "Old", score = -1.2, updatedAt = now - 120 * MILLIS_PER_DAY),
            // "Not for me" ten years ago, score since pushed positive: still out.
            row(
                "library://track/disliked", "Disliked", score = 0.5,
                updatedAt = now, dislikedAt = now - 3650 * MILLIS_PER_DAY
            ),
            // Skipped before half of it 30 days ago and not played since: out until day 60.
            row(
                "library://track/skipped", "Skipped", score = -0.05,
                updatedAt = now, lastSkippedAt = now - 30 * MILLIS_PER_DAY
            ),
            // A hard skip (-0.60) 30 days ago, then a full listen: -0.32, below the score
            // line, but the full listen brings it back.
            row(
                "library://track/listened", "Listened", score = -0.32,
                updatedAt = now - 10 * MILLIS_PER_DAY, lastSkippedAt = now - 30 * MILLIS_PER_DAY,
                lastPositiveAt = now - 10 * MILLIS_PER_DAY
            ),
            // A hard skip 30 days ago, then a 40% listen (+0.08: -0.52), which the query
            // does not count as a full listen: still out.
            row(
                "library://track/partial", "Partial", score = -0.52,
                updatedAt = now - 10 * MILLIS_PER_DAY, lastSkippedAt = now - 30 * MILLIS_PER_DAY
            ),
            // Skipped 30 days ago, then liked: back in mixes.
            row(
                "library://track/liked", "Liked", score = 0.5,
                updatedAt = now, lastSkippedAt = now - 30 * MILLIS_PER_DAY,
                lastPositiveAt = now - 5 * MILLIS_PER_DAY
            ),
            // Disliked, then liked: a dislike stays.
            row(
                "library://track/dislikedliked", "Disliked Liked", score = 0.5,
                updatedAt = now, dislikedAt = now - 20 * MILLIS_PER_DAY,
                lastSkippedAt = now - 30 * MILLIS_PER_DAY, lastPositiveAt = now - 5 * MILLIS_PER_DAY
            ),
            // A full listen BEFORE the latest skip does not bring it back.
            row(
                "library://track/relapsed", "Relapsed", score = 0.0,
                updatedAt = now, lastSkippedAt = now - 5 * MILLIS_PER_DAY,
                lastPositiveAt = now - 20 * MILLIS_PER_DAY
            ),
            // A negative score with no real skip and a like: the score rule applies.
            row(
                "library://track/lowscore", "Low Score", score = -0.5,
                updatedAt = now, lastPositiveAt = now - 5 * MILLIS_PER_DAY
            ),
            // Skipped 90 days ago and listened back above zero: in mixes again.
            row(
                "library://track/forgiven", "Forgiven", score = 0.2,
                updatedAt = now, lastSkippedAt = now - 90 * MILLIS_PER_DAY
            ),
            // A mild skip 90 days ago, still slightly negative: above -0.15, in mixes.
            row(
                "library://track/mild", "Mild", score = -0.1,
                updatedAt = now, lastSkippedAt = now - 90 * MILLIS_PER_DAY
            ),
        )

        assertThat(repo.getMixExcludedTracks().uris).containsExactly(
            "library://track/fresh",
            "library://track/old",
            "library://track/disliked",
            "library://track/skipped",
            "library://track/partial",
            "library://track/dislikedliked",
            "library://track/relapsed",
            "library://track/lowscore"
        )
    }

    @Test
    fun `only a skip before half of the track reaches the exclusion query`() = runTest {
        val feedback = mutableListOf<List<SmartFeedbackEntity>>()
        coEvery { dao.insertSmartFeedback(capture(feedback)) } returns Unit
        val realSkip = slot<Double>()
        coEvery { dao.getSuppressionCandidates(any(), any(), capture(realSkip), any()) } returns emptyList()

        // 180 s track: 40% heard is a real skip, 70% is not.
        repo.recordSkip(track, artists, listenedMs = 72_000)
        repo.recordSkip(track, artists, listenedMs = 126_000)
        repo.getMixExcludedTracks()

        val at40 = feedback[0].single().signal
        val at70 = feedback[1].single().signal
        assertThat(realSkip.captured).isEqualTo(SmartListeningRepositoryImpl.REAL_SKIP_STORED_SIGNAL)
        assertThat(at40).isAtMost(realSkip.captured)
        assertThat(at70).isGreaterThan(realSkip.captured)
    }

    @Test
    fun `only a full listen counts as bringing a skipped track back`() = runTest {
        val feedback = mutableListOf<List<SmartFeedbackEntity>>()
        coEvery { dao.insertSmartFeedback(capture(feedback)) } returns Unit
        val fullListen = slot<List<Double>>()
        coEvery { dao.getSuppressionCandidates(any(), any(), any(), capture(fullListen)) } returns emptyList()

        // 180 s track: a full listen, organic and from a Smart Mix, then 40% and 60%.
        repo.recordListen(track, artists, listenedMs = 180_000, origin = PlayOrigin.ORGANIC)
        repo.recordListen(track, artists, listenedMs = 180_000, origin = PlayOrigin.SMART_MIX)
        repo.recordListen(track, artists, listenedMs = 72_000, origin = PlayOrigin.ORGANIC)
        repo.recordListen(track, artists, listenedMs = 108_000, origin = PlayOrigin.ORGANIC)
        repo.getMixExcludedTracks()

        val stored = feedback.map { it.single().signal }
        assertThat(fullListen.captured).containsExactly(stored[0], stored[1])
        assertThat(fullListen.captured).containsNoneOf(stored[2], stored[3])
    }

    @Test
    fun `a skipped track is keyed only when it credits one artist`() = runTest {
        // A 20 s skip writes -0.45, so the row is out for its score as well as the skip.
        val now = System.currentTimeMillis()
        coEvery { dao.getSuppressionCandidates(any(), any(), any(), any()) } returns listOf(
            row(
                "library://track/duet", "Duet", score = -0.45, updatedAt = now,
                artists = "Alma${SUPPRESSED_ARTIST_SEPARATOR}Zed", lastSkippedAt = now - MILLIS_PER_DAY
            ),
            row("library://track/solo", "Solo", score = -0.45, updatedAt = now, lastSkippedAt = now - MILLIS_PER_DAY),
        )

        val excluded = repo.getMixExcludedTracks()

        assertThat(excluded.uris).containsExactly("library://track/duet", "library://track/solo")
        assertThat(excluded.keys).containsExactly(trackIdentityKey("Some Artist", "Solo"))
    }

    @Test
    fun `the dao is asked for skips of the last 60 days`() = runTest {
        val since = slot<Long>()
        coEvery { dao.getSuppressionCandidates(any(), capture(since), any(), any()) } returns emptyList()

        // The repository reads its own clock, so the cutoff is bounded by the clock
        // before and after the call rather than by a fixed tolerance.
        val before = System.currentTimeMillis()
        repo.getMixExcludedTracks()
        val after = System.currentTimeMillis()

        assertThat(since.captured).isAtLeast(before - MIX_SKIP_EXCLUSION_DAYS * MILLIS_PER_DAY)
        assertThat(since.captured).isAtMost(after - MIX_SKIP_EXCLUSION_DAYS * MILLIS_PER_DAY)
    }

    @Test
    fun `identity keys follow the same decision`() = runTest {
        val now = System.currentTimeMillis()
        coEvery { dao.getSuppressionCandidates(any(), any(), any(), any()) } returns listOf(
            row("library://track/fresh", "Fresh", score = -1.2, updatedAt = now),
            row("library://track/liked", "Liked", score = 0.5, updatedAt = now),
            // No title, so no identity: matched on the uri alone.
            row("library://track/untitled", "", score = -1.2, updatedAt = now),
        )

        assertThat(repo.getMixExcludedTracks().keys).containsExactly(trackIdentityKey("Some Artist", "Fresh"))
        assertThat(repo.getMixExcludedTracks().uris)
            .containsExactly("library://track/fresh", "library://track/untitled")
    }

    @Test
    fun `a disliked track with two artists is matched whichever of them leads`() = runTest {
        // The database keeps no artist order, while a mix candidate is keyed by its lead
        // artist. Keying only the alphabetically first name let "Zed feat. Alma" back in.
        val now = System.currentTimeMillis()
        coEvery { dao.getSuppressionCandidates(any(), any(), any(), any()) } returns listOf(
            row(
                "library://track/duet", "Duet", score = 0.0, updatedAt = now, dislikedAt = now,
                artists = "Alma${SUPPRESSED_ARTIST_SEPARATOR}Zed"
            ),
        )

        assertThat(repo.getMixExcludedTracks().keys)
            .containsExactly(trackIdentityKey("Alma", "Duet"), trackIdentityKey("Zed", "Duet"))
    }

    private fun row(
        uri: String,
        name: String,
        score: Double,
        updatedAt: Long,
        dislikedAt: Long? = null,
        artists: String = "Some Artist",
        lastSkippedAt: Long? = null,
        lastPositiveAt: Long? = null,
    ) = SuppressedTrackRow(
        uri = uri,
        trackName = name,
        artistNames = artists,
        score = score,
        scoreUpdatedAt = updatedAt,
        dislikedAt = dislikedAt,
        lastSkippedAt = lastSkippedAt,
        lastPositiveAt = lastPositiveAt,
    )

    private companion object {
        /** The repository reads its own clock a few milliseconds after the test. */
        const val CLOCK_TOLERANCE = 1e-6
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
