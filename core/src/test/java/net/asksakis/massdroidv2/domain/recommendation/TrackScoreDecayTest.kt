package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the time-faded track score and the mix exclusion rule built on it.
 *
 * The score used to be a running sum with no time in it, so a track below the
 * line at that time (-0.15) stayed out of mixes for good. Measured on a real database on 2026-10-05, that combination had
 * kept 530 tracks out of mixes on a single skip and 1158 more whose feedback had
 * long been pruned, 11% of the library in all. These tests assert exact values so
 * a wrong half-life, a wrong threshold or a sign error in the decay turns them red.
 */
class TrackScoreDecayTest {

    @Test
    fun `a score written just now has not faded`() {
        assertThat(effectiveTrackScore(-0.6, T0, T0)).isWithin(TOLERANCE).of(-0.6)
    }

    @Test
    fun `a score loses half its weight after one half-life`() {
        assertThat(effectiveTrackScore(-0.6, T0, T0 + days(60))).isWithin(TOLERANCE).of(-0.3)
    }

    @Test
    fun `a score keeps a quarter of its weight after two half-lives`() {
        assertThat(effectiveTrackScore(-0.6, T0, T0 + days(120))).isWithin(TOLERANCE).of(-0.15)
    }

    @Test
    fun `a positive score fades the same way`() {
        assertThat(effectiveTrackScore(1.2, T0, T0 + days(60))).isWithin(TOLERANCE).of(0.6)
        assertThat(effectiveTrackScore(1.2, T0, T0 + days(180))).isWithin(TOLERANCE).of(0.15)
    }

    @Test
    fun `a clock that moved backwards does not grow the score`() {
        assertThat(effectiveTrackScore(-0.6, T0, T0 - days(10))).isWithin(TOLERANCE).of(-0.6)
    }

    @Test
    fun `a new signal is added to the faded score, not to the stored one`() {
        // Stored +0.28 sixty days ago, so it counts 0.14 now; a hard skip on top
        // lands at 0.14 - 0.60. Adding to the stored value would give -0.32.
        val updated = addTrackSignal(stored = 0.28, updatedAt = T0, delta = -0.6, now = T0 + days(60))
        assertThat(updated).isWithin(TOLERANCE).of(-0.46)
    }

    @Test
    fun `two hard skips add up on the same day`() {
        val first = addTrackSignal(stored = 0.0, updatedAt = 0L, delta = HARD_SKIP, now = T0)
        assertThat(first).isWithin(TOLERANCE).of(-0.6)
        val second = addTrackSignal(stored = first, updatedAt = T0, delta = HARD_SKIP, now = T0)
        assertThat(second).isWithin(TOLERANCE).of(-1.2)
    }

    @Test
    fun `the first of two hard skips has faded when the second arrives`() {
        // -0.6 * 0.5^1.5 - 0.6 = -0.8121... after 90 days, -0.75 after 120.
        val first = addTrackSignal(stored = 0.0, updatedAt = 0L, delta = HARD_SKIP, now = T0)
        val after90 = addTrackSignal(stored = first, updatedAt = T0, delta = HARD_SKIP, now = T0 + days(90))
        val after120 = addTrackSignal(stored = first, updatedAt = T0, delta = HARD_SKIP, now = T0 + days(120))
        assertThat(after90).isWithin(1e-6).of(-0.812132)
        assertThat(after120).isWithin(TOLERANCE).of(-0.75)
    }

    @Test
    fun `an old dislike keeps a track out of mixes after its score has faded`() {
        val oldDislike = effectiveTrackScore(-2.0, T0, T0 + days(3650))
        assertThat(excluded(oldDislike, dislikedAt = T0, now = T0 + days(3650))).isTrue()
    }

    @Test
    fun `a skip within 60 days keeps a track out of mixes`() {
        // A mild skip leaves a score that rounds to zero, so only the skip date decides.
        assertThat(excluded(0.0, skippedAt = T0 - days(59))).isTrue()
        assertThat(excluded(0.0, skippedAt = T0 - days(61))).isFalse()
    }

    @Test
    fun `a like or a full listen after the last skip brings the track back, score included`() {
        // A hard skip (-0.60) and then a full listen leave -0.32, below the score line.
        assertThat(excluded(-0.32, skippedAt = T0 - days(30), positiveAt = T0 - days(10))).isFalse()
        // A like or a full listen before the latest skip does not count.
        assertThat(excluded(0.0, skippedAt = T0 - days(30), positiveAt = T0 - days(40))).isTrue()
    }

    @Test
    fun `a dislike stays after a like`() {
        assertThat(excluded(0.5, dislikedAt = T0 - days(1), skippedAt = T0 - days(30), positiveAt = T0)).isTrue()
    }

    @Test
    fun `a negative score without a real skip is judged by the score alone`() {
        assertThat(excluded(-0.5, positiveAt = T0)).isTrue()
    }

    @Test
    fun `an effective score below -0_15 keeps a track out of mixes`() {
        assertThat(excluded(-0.2)).isTrue()
        assertThat(excluded(-0.1)).isFalse()
        // A hard skip of -0.60 is back after two half-lives, about four months.
        val hardSkip = effectiveTrackScore(-0.6, T0, T0 + days(125))
        assertThat(excluded(hardSkip, skippedAt = T0, now = T0 + days(125))).isFalse()
        assertThat(excluded(0.3)).isFalse()
    }

    @Test
    fun `a dislike keeps a track out of mixes whatever its score`() {
        assertThat(excluded(2.5, dislikedAt = T0 - days(3650))).isTrue()
    }

    private fun excluded(
        effective: Double,
        dislikedAt: Long? = null,
        skippedAt: Long? = null,
        positiveAt: Long? = null,
        now: Long = T0
    ) = isTrackExcludedFromMixes(effective, dislikedAt, skippedAt, positiveAt, now)

    @Test
    fun `a non-negative seed floor is also the stored floor`() {
        assertThat(storedTrackScoreFloor(0.3)).isEqualTo(0.3)
        assertThat(storedTrackScoreFloor(0.0)).isEqualTo(0.0)
    }

    @Test
    fun `a negative seed floor cannot exclude any stored score`() {
        // -3.0 stored long ago fades above -0.5, so a stored floor of -0.5 would
        // have dropped a row that qualifies.
        assertThat(effectiveTrackScore(-3.0, T0, T0 + days(365))).isGreaterThan(-0.5)
        assertThat(storedTrackScoreFloor(-0.5)).isLessThan(-3.0)
    }

    @Test
    fun `a hard skip is forgotten once it fades below the forget line`() {
        // -0.6 needs log2(12), about 3.6 half-lives (215 days), to fall under 0.05.
        assertThat(isTrackScoreForgotten(-0.6, T0, T0 + days(210))).isFalse()
        assertThat(isTrackScoreForgotten(-0.6, T0, T0 + days(220))).isTrue()
    }

    @Test
    fun `a positive score is forgotten by magnitude too`() {
        // +0.28 falls under 0.05 after log2(5.6), about 2.5 half-lives (149 days).
        assertThat(isTrackScoreForgotten(0.28, T0, T0 + days(145))).isFalse()
        assertThat(isTrackScoreForgotten(0.28, T0, T0 + days(155))).isTrue()
    }

    @Test
    fun `a fresh small signal counts as forgotten at once`() {
        // The mildest skip (-0.03) never reaches the forget line. The cleanup keeps
        // such a track anyway while its feedback rows exist, so this alone does not
        // remove it.
        assertThat(isTrackScoreForgotten(-0.03, T0, T0)).isTrue()
        assertThat(isTrackScoreForgotten(-0.05, T0, T0)).isFalse()
    }

    private companion object {
        const val TOLERANCE = 1e-9
        const val HARD_SKIP = -0.6
        const val T0 = 1_760_000_000_000L
        const val MILLIS_PER_DAY = 86_400_000L
        fun days(n: Int): Long = n * MILLIS_PER_DAY
    }
}
