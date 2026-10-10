package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.database.TrackUriScoreRow
import org.junit.Test

/**
 * Pins which unplayed tracks the cleanup forgets.
 *
 * The old sweep removed only tracks whose stored score was exactly 0. The fading is
 * applied on read, so on a real database 19,881 of 19,886 tracks had a non-zero
 * score and the sweep removed nothing. These tests decide on the faded score.
 */
class ForgottenTrackSweepTest {

    private val repo = PlayHistoryRepositoryImpl(
        dao = mockk(relaxed = true),
        json = Json { ignoreUnknownKeys = true },
        appDatabase = mockk(relaxed = true),
        genreSpellings = mockk(relaxed = true),
    )

    @Test
    fun `a skip that has faded below the forget line is swept`() {
        val rows = listOf(TrackUriScoreRow("library://track/1", -0.6, NOW - days(220)))

        assertThat(repo.forgottenTrackUris(rows, NOW)).containsExactly("library://track/1")
    }

    @Test
    fun `a recent skip is kept`() {
        val rows = listOf(TrackUriScoreRow("library://track/2", -0.6, NOW - days(30)))

        assertThat(repo.forgottenTrackUris(rows, NOW)).isEmpty()
    }

    @Test
    fun `a strong positive score is kept until it fades too`() {
        val rows = listOf(
            TrackUriScoreRow("library://track/3", 1.2, NOW - days(120)),
            TrackUriScoreRow("library://track/4", 0.28, NOW - days(160)),
        )

        assertThat(repo.forgottenTrackUris(rows, NOW)).containsExactly("library://track/4")
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val MILLIS_PER_DAY = 86_400_000L
        fun days(n: Int): Long = n * MILLIS_PER_DAY
    }
}
