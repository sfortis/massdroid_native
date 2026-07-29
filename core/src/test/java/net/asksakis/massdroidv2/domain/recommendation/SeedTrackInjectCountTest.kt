package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.repository.SeedTrack
import org.junit.Test

/** Pins the softened loved-track injection quota (no floor, Discovery-tapered). */
class SeedTrackInjectCountTest {

    @Test
    fun `high discovery tapers injection to zero`() {
        assertThat(seedTrackInjectCount(discovery = 1.0, target = 40)).isEqualTo(0)
    }

    @Test
    fun `zero discovery reserves up to the max fraction`() {
        // (1 - 0) * 0.30 * 40 = 12
        assertThat(seedTrackInjectCount(discovery = 0.0, target = 40)).isEqualTo(12)
    }

    @Test
    fun `mid discovery reserves a proportional slice`() {
        // (1 - 0.5) * 0.30 * 40 = 6
        assertThat(seedTrackInjectCount(discovery = 0.5, target = 40)).isEqualTo(6)
        // (1 - 0.875) * 0.30 * 40 = 1.5 -> 2
        assertThat(seedTrackInjectCount(discovery = 0.875, target = 40)).isEqualTo(2)
    }

    @Test
    fun `out-of-range discovery never yields a negative count`() {
        assertThat(seedTrackInjectCount(discovery = 1.5, target = 40)).isEqualTo(0)
    }

    // --- varietyWindow: spans the full 0..1 range with no plateau ---

    @Test
    fun `varietyWindow is 1 at zero and the full pool at one`() {
        assertThat(varietyWindow(0.0, n = 20)).isEqualTo(1)
        assertThat(varietyWindow(1.0, n = 20)).isEqualTo(20)
    }

    @Test
    fun `varietyWindow keeps growing through the upper half (no 0_66 plateau)`() {
        // The old logic was identical for every value >= 0.66; the new one must
        // keep widening past it.
        assertThat(varietyWindow(0.7, n = 20)).isLessThan(varietyWindow(0.9, n = 20))
        assertThat(varietyWindow(0.5, n = 20)).isLessThan(varietyWindow(0.8, n = 20))
    }

    @Test
    fun `varietyWindow stays within bounds`() {
        assertThat(varietyWindow(0.0, n = 1)).isEqualTo(1)
        assertThat(varietyWindow(1.5, n = 5)).isEqualTo(5)
    }

    // --- strictnessRankedPool: recency (low) -> score (high) ---

    private fun seed(uri: String, score: Double) =
        SeedTrack(trackUri = uri, trackName = uri, artistName = uri, lastPlayedAt = 0L, score = score)

    // Pool in recency order (index 0 = most recent). The most-recent track is
    // low-scored; a later (older) track is the most-loved.
    private val recencyPool = listOf(
        seed("recent", score = 0.1),
        seed("mid", score = 0.5),
        seed("loved", score = 3.0),
        seed("old", score = 0.2),
    )

    @Test
    fun `low strictness keeps the recency order`() {
        val ranked = strictnessRankedPool(recencyPool, strictness = 0.0)
        assertThat(ranked.first().trackUri).isEqualTo("recent")
    }

    @Test
    fun `high strictness promotes the most-loved track to the front`() {
        val ranked = strictnessRankedPool(recencyPool, strictness = 1.0)
        assertThat(ranked.first().trackUri).isEqualTo("loved")
    }

    @Test
    fun `strictness genuinely changes the seed ordering`() {
        val low = strictnessRankedPool(recencyPool, 0.0).map { it.trackUri }
        val high = strictnessRankedPool(recencyPool, 1.0).map { it.trackUri }
        assertThat(low).isNotEqualTo(high)
    }

    // --- genre movement between mixes (no whiplash, no lock-in) ---
    // Pins the cluster-whiplash fix: a mix that stays keeps the recent genre
    // family, a mix that hops leaves it. Variety decides HOW OFTEN we hop.

    private val recentElectronic = setOf("electronic")

    @Test
    fun `a staying mix prefers the recent family`() {
        // deep house session (electronic family) -> prefer electronic, reject rock.
        assertThat(prefersCandidateFamily(setOf("electronic"), recentElectronic, hop = false)).isTrue()
        assertThat(prefersCandidateFamily(setOf("rock"), recentElectronic, hop = false)).isFalse()
    }

    @Test
    fun `a hopping mix prefers a different family`() {
        // exploration: reject the recent family, prefer a foreign one.
        assertThat(prefersCandidateFamily(setOf("electronic"), recentElectronic, hop = true)).isFalse()
        assertThat(prefersCandidateFamily(setOf("rock"), recentElectronic, hop = true)).isTrue()
    }

    @Test
    fun `no recent families means no preference`() {
        // First run / cleared history: nothing preferred, caller falls back to the fresh pool.
        assertThat(prefersCandidateFamily(setOf("electronic"), emptySet(), hop = false)).isFalse()
        assertThat(prefersCandidateFamily(setOf("rock"), emptySet(), hop = true)).isFalse()
    }

    @Test
    fun `variety is the probability of hopping, and the extremes are absolute`() {
        val random = kotlin.random.Random(42)
        assertThat((1..50).count { shouldHopFamily(0.0, random) }).isEqualTo(0)
        assertThat((1..50).count { shouldHopFamily(1.0, random) }).isEqualTo(50)
        // Mid slider: both outcomes occur, so consecutive mixes are not locked
        // into one behaviour the way the old 0.66 threshold locked them.
        val mid = (1..200).count { shouldHopFamily(0.5, random) }
        assertThat(mid).isGreaterThan(60)
        assertThat(mid).isLessThan(140)
    }

    @Test
    fun `a higher variety hops more often than a lower one`() {
        val low = (1..400).count { shouldHopFamily(0.2, kotlin.random.Random(7 + it)) }
        val high = (1..400).count { shouldHopFamily(0.8, kotlin.random.Random(7 + it)) }
        assertThat(low).isLessThan(high)
    }
}
