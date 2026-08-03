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
import net.asksakis.massdroidv2.data.database.SmartFeedbackEntity
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * Pins the explicit dislike: the one negative signal the engine does not have to
 * infer.
 *
 * It matters that this is exact rather than approximately right. Every other
 * negative signal is a guess read out of behaviour, so this is the one the
 * listener can point at and say "I meant that" - and it is destructive, since a
 * disliked track is meant never to surface again. The undo has to put back
 * precisely what was taken.
 */
class DislikeSignalTest {

    /** Pass-through: the production runner is Room's, which needs a device. */
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
    fun `a dislike sets the score outright instead of nudging it`() = runTest {
        // A track the listener once loved can sit well above the suppression
        // line, so a delta could not promise to bury it. Only an absolute
        // write can.
        coEvery { dao.getTrackScore(any()) } returns 4.2

        repo.recordDislike(track, artists)

        val score = slot<Double>()
        coVerify { dao.setTrackScore("library://track/1", capture(score)) }
        assertThat(score.captured).isLessThan(SUPPRESSION_THRESHOLD)
        coVerify(exactly = 0) { dao.adjustTrackScore(any(), any()) }
    }

    @Test
    fun `the artist is only brushed, never condemned`() = runTest {
        coEvery { dao.getTrackScore(any()) } returns 0.0

        val receipt = repo.recordDislike(track, artists)!!

        // The same dampening a skip gets: one bad track is not a verdict on
        // whoever made it. Blocking the artist is a separate, deliberate act.
        assertThat(receipt.artistSignal).isWithin(TOLERANCE).of(-0.175)
        val rows = slot<List<SmartFeedbackEntity>>()
        coVerify { dao.insertSmartFeedback(capture(rows)) }
        assertThat(rows.captured.map { it.action }).containsExactly("dislike")
        assertThat(rows.captured.single().signal).isWithin(TOLERANCE).of(-0.175)
    }

    @Test
    fun `the receipt carries the score the track had before`() = runTest {
        coEvery { dao.getTrackScore(any()) } returns 1.75

        val receipt = repo.recordDislike(track, artists)!!

        assertThat(receipt.previousScore).isWithin(TOLERANCE).of(1.75)
        assertThat(receipt.trackKey).isEqualTo("library://track/1")
        assertThat(receipt.artistUris).containsExactly("library://artist/9")
    }

    @Test
    fun `an unscored track is treated as starting from zero`() = runTest {
        coEvery { dao.getTrackScore(any()) } returns null

        val receipt = repo.recordDislike(track, artists)!!

        assertThat(receipt.previousScore).isEqualTo(0.0)
    }

    @Test
    fun `undo restores the exact score and removes exactly the rows written`() = runTest {
        coEvery { dao.getTrackScore(any()) } returns 1.75
        coEvery { dao.restoreTrackScoreIfUnchanged(any(), any(), any()) } returns 1
        val receipt = repo.recordDislike(track, artists)!!

        repo.undoDislike(receipt)

        // Conditional on the score still being the one the dislike wrote, so a
        // newer opinion is never silently rolled back by an older undo.
        coVerify { dao.restoreTrackScoreIfUnchanged("library://track/1", -2.0, 1.75) }
        // Matched on the same timestamp the rows were written with, so an undo
        // cannot take out an older dislike of the same track.
        coVerify { dao.deleteSmartFeedback("library://track/1", "dislike", receipt.createdAt) }
    }

    @Test
    fun `the rows written carry the timestamp the undo will look for`() = runTest {
        coEvery { dao.getTrackScore(any()) } returns 0.0

        val rows = slot<List<SmartFeedbackEntity>>()
        val receipt = repo.recordDislike(track, artists)!!
        coVerify { dao.insertSmartFeedback(capture(rows)) }

        // Without this the undo would delete nothing and the artist would keep
        // a penalty the listener took back.
        assertThat(rows.captured.map { it.createdAt }).containsExactly(receipt.createdAt)
    }

    @Test
    fun `an undo whose track was scored again leaves the newer score alone`() = runTest {
        // Found in review: the undo used to write the old score unconditionally,
        // so disliking a track, listening to it again and then undoing threw
        // away the listen.
        coEvery { dao.getTrackScore(any()) } returns 0.0
        coEvery { dao.restoreTrackScoreIfUnchanged(any(), any(), any()) } returns 0
        val receipt = repo.recordDislike(track, artists)!!

        repo.undoDislike(receipt)

        // The feedback row still goes: the listener did take the dislike back.
        coVerify { dao.deleteSmartFeedback("library://track/1", "dislike", receipt.createdAt) }
        // But no blind write to the score.
        coVerify(exactly = 1) { dao.setTrackScore(any(), any()) }   // only the dislike itself
    }

    @Test
    fun `with Smart Listening off nothing is recorded and there is nothing to undo`() = runTest {
        every { settings.smartListeningEnabled } returns flowOf(false)

        assertThat(repo.recordDislike(track, artists)).isNull()
        coVerify(exactly = 0) { dao.setTrackScore(any(), any()) }
    }

    private companion object {
        /** `PlayHistoryDao.getSuppressedTrackUris` hides anything below this. */
        const val SUPPRESSION_THRESHOLD = -0.15
        const val TOLERANCE = 1e-9
    }
}
