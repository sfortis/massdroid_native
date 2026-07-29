package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.repository.SeedTrack
import kotlin.random.Random
import org.junit.Test

/**
 * Pins the score-weighted draw for the non-primary seeds.
 *
 * Why it matters: the seed pool is dominated by tracks played exactly once, which
 * are mostly tracks the engine itself queued and the listener merely did not skip
 * (measured on a real 30-day history: 88% of the deduped pool, mean score 0.267 vs
 * 0.703 for repeat-played). Those used to be drawn by a plain `shuffled()`, so
 * Strictness never reached them and every mix was a mutation of the previous one.
 */
class SeedWeightedOrderTest {

    private fun seed(name: String, score: Double) =
        SeedTrack(
            trackUri = "library://track/$name",
            trackName = name,
            artistName = name,
            lastPlayedAt = 0L,
            score = score
        )

    /** A pool shaped like the real one: a few loved tracks, a long passive tail. */
    private fun pool() =
        listOf(seed("loved-a", 1.9), seed("loved-b", 1.2)) +
            (1..18).map { seed("passive-$it", 0.25) }

    private fun topPickCounts(strictness: Double, runs: Int = 4000): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        repeat(runs) { i ->
            val first = strictnessWeightedOrder(pool(), strictness, Random(i)).first()
            counts[first.trackName] = (counts[first.trackName] ?: 0) + 1
        }
        return counts
    }

    @Test
    fun `at full strictness the loved tracks lead far more often than the passive tail`() {
        val counts = topPickCounts(strictness = 1.0)
        val loved = (counts["loved-a"] ?: 0) + (counts["loved-b"] ?: 0)
        // Two loved tracks out of twenty: a uniform draw would lead ~10% of runs.
        // Weighted by score they must dominate well beyond that.
        assertThat(loved).isGreaterThan((4000 * 0.30).toInt())
        // The best-scored one leads more often than the merely good one.
        assertThat(counts["loved-a"] ?: 0).isGreaterThan(counts["loved-b"] ?: 0)
    }

    @Test
    fun `at zero strictness the draw stays uniform`() {
        val counts = topPickCounts(strictness = 0.0)
        val loved = (counts["loved-a"] ?: 0) + (counts["loved-b"] ?: 0)
        // 2 of 20 items -> ~10% (=400). Generous band, this only has to prove the
        // old plain-shuffle behaviour survives at the bottom of the slider.
        assertThat(loved).isIn(250..600)
    }

    @Test
    fun `raising strictness moves monotonically toward the loved tracks`() {
        val low = topPickCounts(0.25).filterKeys { it.startsWith("loved") }.values.sum()
        val mid = topPickCounts(0.6).filterKeys { it.startsWith("loved") }.values.sum()
        val high = topPickCounts(1.0).filterKeys { it.startsWith("loved") }.values.sum()
        assertThat(low).isLessThan(mid)
        assertThat(mid).isLessThan(high)
    }

    @Test
    fun `the passive tail is still reachable, this is a draw and not a ranking`() {
        // Consecutive mixes must keep differing: even at full strictness a
        // low-scored seed has to surface sometimes, or rotation dies.
        val counts = topPickCounts(strictness = 1.0)
        val passive = counts.filterKeys { it.startsWith("passive") }.values.sum()
        assertThat(passive).isGreaterThan(0)
    }

    @Test
    fun `the result is a permutation, nothing is dropped or duplicated`() {
        val input = pool()
        val out = strictnessWeightedOrder(input, 0.95, Random(7))
        assertThat(out).hasSize(input.size)
        assertThat(out.map { it.trackUri }.toSet()).isEqualTo(input.map { it.trackUri }.toSet())
    }

    @Test
    fun `the same random seed gives the same order`() {
        val a = strictnessWeightedOrder(pool(), 0.8, Random(42)).map { it.trackUri }
        val b = strictnessWeightedOrder(pool(), 0.8, Random(42)).map { it.trackUri }
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `a flat cluster and tiny inputs are handled`() {
        val flat = (1..5).map { seed("same-$it", 0.5) }
        assertThat(strictnessWeightedOrder(flat, 1.0, Random(1))).hasSize(5)
        assertThat(strictnessWeightedOrder(emptyList(), 1.0, Random(1))).isEmpty()
        val one = listOf(seed("only", 0.4))
        assertThat(strictnessWeightedOrder(one, 1.0, Random(1))).isEqualTo(one)
    }

    @Test
    fun `negative scores do not break the weighting`() {
        // tracks.score is a cumulative preference score and goes negative on
        // skips (real pool minimum was -0.32).
        val mixed = listOf(seed("disliked", -0.32), seed("neutral", 0.0), seed("loved", 1.5))
        val out = strictnessWeightedOrder(mixed, 1.0, Random(3))
        assertThat(out).hasSize(3)
        assertThat(out.map { it.trackUri }.toSet()).isEqualTo(mixed.map { it.trackUri }.toSet())
    }

    @Test
    fun `a full-size pool sorts without an inconsistent comparator`() {
        // RECENCY_POOL_LIMIT is 600 and TimSort throws on a comparator that
        // re-rolls its key mid-sort, which is exactly the bug this shape of code
        // invited before the key was materialised once per item.
        val big = (1..600).map { seed("t-$it", it / 600.0) }
        assertThat(strictnessWeightedOrder(big, 0.9, Random(11))).hasSize(600)
    }
}
