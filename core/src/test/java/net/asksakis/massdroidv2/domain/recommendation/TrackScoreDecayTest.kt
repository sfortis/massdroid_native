package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the time-faded track score and the suppression rule built on it.
 *
 * The score used to be a running sum with no time in it, and the suppression line
 * sat at -0.15. Measured on a real database on 2026-10-05, that combination had
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
    fun `one hard skip never suppresses a track`() {
        val afterSkip = addTrackSignal(stored = 0.0, updatedAt = 0L, delta = HARD_SKIP, now = T0)
        assertThat(afterSkip).isWithin(TOLERANCE).of(-0.6)
        assertThat(isTrackSuppressed(effectiveTrackScore(afterSkip, T0, T0), dislikedAt = null)).isFalse()
    }

    @Test
    fun `two hard skips on the same day suppress a track`() {
        val first = addTrackSignal(stored = 0.0, updatedAt = 0L, delta = HARD_SKIP, now = T0)
        val second = addTrackSignal(stored = first, updatedAt = T0, delta = HARD_SKIP, now = T0)
        assertThat(second).isWithin(TOLERANCE).of(-1.2)
        assertThat(isTrackSuppressed(effectiveTrackScore(second, T0, T0), dislikedAt = null)).isTrue()
    }

    @Test
    fun `two hard skips 120 days apart do not suppress a track`() {
        // The first skip has faded to a quarter (-0.15), so the pair lands exactly
        // on the line and the strict comparison keeps the track in.
        val first = addTrackSignal(stored = 0.0, updatedAt = 0L, delta = HARD_SKIP, now = T0)
        val secondAt = T0 + days(120)
        val second = addTrackSignal(stored = first, updatedAt = T0, delta = HARD_SKIP, now = secondAt)
        assertThat(second).isWithin(TOLERANCE).of(-0.75)
        assertThat(isTrackSuppressed(effectiveTrackScore(second, secondAt, secondAt), dislikedAt = null)).isFalse()
    }

    @Test
    fun `two hard skips 90 days apart still suppress a track`() {
        // Inside the window: -0.6 * 0.5^1.5 - 0.6 = -0.8121...
        val first = addTrackSignal(stored = 0.0, updatedAt = 0L, delta = HARD_SKIP, now = T0)
        val secondAt = T0 + days(90)
        val second = addTrackSignal(stored = first, updatedAt = T0, delta = HARD_SKIP, now = secondAt)
        assertThat(second).isWithin(1e-6).of(-0.812132)
        assertThat(isTrackSuppressed(second, dislikedAt = null)).isTrue()
    }

    @Test
    fun `a suppressed track comes back once its score has faded`() {
        // -1.2 after two same-day skips; 60 days later it counts -0.6.
        val faded = effectiveTrackScore(-1.2, T0, T0 + days(60))
        assertThat(faded).isWithin(TOLERANCE).of(-0.6)
        assertThat(isTrackSuppressed(faded, dislikedAt = null)).isFalse()
    }

    @Test
    fun `a dislike suppresses whatever the score and however old it is`() {
        assertThat(isTrackSuppressed(effective = 2.5, dislikedAt = T0)).isTrue()
        assertThat(isTrackSuppressed(effective = 0.0, dislikedAt = T0 - days(3650))).isTrue()
        val oldDislike = effectiveTrackScore(-2.0, T0, T0 + days(3650))
        assertThat(isTrackSuppressed(oldDislike, dislikedAt = T0)).isTrue()
    }

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

    private companion object {
        const val TOLERANCE = 1e-9
        const val HARD_SKIP = -0.6
        const val T0 = 1_760_000_000_000L
        const val MILLIS_PER_DAY = 86_400_000L
        fun days(n: Int): Long = n * MILLIS_PER_DAY
    }
}
