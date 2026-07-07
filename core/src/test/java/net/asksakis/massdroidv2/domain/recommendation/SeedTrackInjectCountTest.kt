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
        // (1 - 0) * 0.4 * 40 = 16
        assertThat(seedTrackInjectCount(discovery = 0.0, target = 40)).isEqualTo(16)
    }

    @Test
    fun `mid discovery reserves a proportional slice`() {
        // (1 - 0.5) * 0.4 * 40 = 8
        assertThat(seedTrackInjectCount(discovery = 0.5, target = 40)).isEqualTo(8)
        // (1 - 0.875) * 0.4 * 40 = 2 (the value observed in the 5-run test)
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
}
