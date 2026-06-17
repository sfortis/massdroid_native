package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.repository.PlayHistoryRepositoryImpl.WeightedPlay
import org.junit.Test

/**
 * Pins the BLL temporal-decay scoring math (ACT-R base-level learning) and the
 * completion-weight curve. The functions are pure; the repo is built with relaxed
 * mock dependencies only so we can reach the @VisibleForTesting instance methods.
 */
class PlayHistoryBllScoringTest {

    private val repo = PlayHistoryRepositoryImpl(
        dao = mockk(relaxed = true),
        json = Json { ignoreUnknownKeys = true },
        appDatabase = mockk(relaxed = true),
    )

    private val hourMs = 3_600_000L
    private val now = 100L * hourMs

    private fun playHoursAgo(hours: Double, weight: Double = 1.0) =
        WeightedPlay(playedAt = now - (hours * hourMs).toLong(), weight = weight)

    // --- computeWeightedBllScore ---

    @Test
    fun `empty history returns the BLL floor`() {
        assertThat(repo.computeWeightedBllScore(now, emptyList())).isEqualTo(-100.0)
    }

    @Test
    fun `a single play one hour ago scores ln(1) = 0`() {
        // hoursAgo=1 -> 1^-1.5 = 1, weight 1, sessionFactor 1 -> sum 1 -> ln(1)=0
        assertThat(repo.computeWeightedBllScore(now, listOf(playHoursAgo(1.0))))
            .isWithin(1e-9).of(0.0)
    }

    @Test
    fun `older plays decay toward the floor`() {
        // 24h: 24^-1.5 ~= 0.0085052 -> ln ~= -4.767
        assertThat(repo.computeWeightedBllScore(now, listOf(playHoursAgo(24.0))))
            .isWithin(0.01).of(-4.767)
    }

    @Test
    fun `weight scales the activation linearly`() {
        // hoursAgo=1, weight 2 -> sum 2 -> ln(2)
        assertThat(repo.computeWeightedBllScore(now, listOf(playHoursAgo(1.0, weight = 2.0))))
            .isWithin(1e-6).of(0.6931471805599453)
    }

    @Test
    fun `more recent history outscores older history`() {
        val recent = repo.computeWeightedBllScore(now, listOf(playHoursAgo(1.0)))
        val old = repo.computeWeightedBllScore(now, listOf(playHoursAgo(48.0)))
        assertThat(recent).isGreaterThan(old)
    }

    @Test
    fun `consecutive in-session plays are dampened by SESSION_DECAY`() {
        // pA 2h ago (sessionCount 0, factor 1) + pB 1h50m ago, 10 min after pA
        // (< 30 min gap -> sessionCount 1, factor 0.7).
        // term(pA)=2^-1.5=0.353553 ; term(pB)=(1.8333)^-1.5*0.7=0.281990 ; ln(sum)~=-0.453
        val plays = listOf(playHoursAgo(2.0), playHoursAgo(110.0 / 60.0))
        assertThat(repo.computeWeightedBllScore(now, plays)).isWithin(0.01).of(-0.453)
    }

    // --- completionWeight ---

    @Test
    fun `completionWeight is 1 when listened or duration is unknown`() {
        assertThat(repo.completionWeight(listenedMs = null, durationSec = 180.0)).isEqualTo(1.0)
        assertThat(repo.completionWeight(listenedMs = 60_000, durationSec = null)).isEqualTo(1.0)
        assertThat(repo.completionWeight(listenedMs = 60_000, durationSec = 0.0)).isEqualTo(1.0)
    }

    @Test
    fun `completionWeight maps listen ratio onto the floor-plus-range curve`() {
        // full listen -> 0.3 + 0.7*1.0 (binary fp: assert within tolerance)
        assertThat(repo.completionWeight(listenedMs = 180_000, durationSec = 180.0)).isWithin(1e-9).of(1.0)
        // half listen -> 0.3 + 0.7*0.5
        assertThat(repo.completionWeight(listenedMs = 90_000, durationSec = 180.0)).isWithin(1e-9).of(0.65)
        // zero listen -> floor
        assertThat(repo.completionWeight(listenedMs = 0, durationSec = 180.0)).isWithin(1e-9).of(0.3)
        // over-listen clamps the ratio at 1.0
        assertThat(repo.completionWeight(listenedMs = 999_999_999, durationSec = 180.0)).isWithin(1e-9).of(1.0)
    }
}
