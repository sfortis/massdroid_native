package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Album
import org.junit.Test
import kotlin.random.Random

/** Pure tests for the MMR re-ranker math + Jaccard similarity. */
class RecommendationEngineTest {

    private val engine = RecommendationEngine()

    private fun scored(id: String, relevance: Double, genres: Set<String>) =
        ScoredAlbum(
            album = Album(itemId = id, provider = "p", name = id, uri = id),
            genres = genres,
            relevance = relevance,
        )

    // --- jaccardSimilarity ---

    @Test
    fun `jaccardSimilarity is intersection over union`() {
        assertThat(engine.jaccardSimilarity(setOf("a", "b"), setOf("b", "c")))
            .isWithin(1e-9).of(1.0 / 3.0)
        assertThat(engine.jaccardSimilarity(setOf("a", "b"), setOf("a", "b"))).isEqualTo(1.0)
        assertThat(engine.jaccardSimilarity(setOf("a"), setOf("b"))).isEqualTo(0.0)
        assertThat(engine.jaccardSimilarity(emptySet(), emptySet())).isEqualTo(0.0)
        assertThat(engine.jaccardSimilarity(setOf("a"), emptySet())).isEqualTo(0.0)
    }

    // --- mmrRerank ---

    @Test
    fun `mmrRerank still fills the count when later picks have negative MMR`() {
        // Two same-genre albums: after the first is picked, the second's MMR goes
        // negative (diversity penalty > relevance). The old Double.MIN_VALUE sentinel
        // left it unselected (short mix); the fix must return both.
        val items = listOf(
            scored("hi", relevance = 1.0, genres = setOf("rock")),
            scored("lo", relevance = 0.9, genres = setOf("rock")),
        )
        val out = engine.mmrRerank(items, lambda = 0.5, count = 2, random = Random(1)) { it.genres }
        assertThat(out).hasSize(2)
    }

    @Test
    fun `mmrRerank never returns more than count and is deterministic for a seed`() {
        val items = listOf(
            scored("a", 1.0, setOf("rock")),
            scored("b", 0.8, setOf("jazz")),
            scored("c", 0.6, setOf("pop")),
        )
        val out = engine.mmrRerank(items, lambda = 0.5, count = 2, random = Random(5)) { it.genres }
        assertThat(out).hasSize(2)
        val again = engine.mmrRerank(items, lambda = 0.5, count = 2, random = Random(5)) { it.genres }
        assertThat(out).isEqualTo(again)
    }

    @Test
    fun `mmrRerank returns all items when count exceeds the pool`() {
        val items = listOf(scored("a", 1.0, setOf("rock")), scored("b", 0.5, setOf("jazz")))
        val out = engine.mmrRerank(items, lambda = 0.5, count = 10, random = Random(3)) { it.genres }
        assertThat(out).hasSize(2)
    }

    @Test
    fun `mmrRerank on empty input is empty`() {
        val out = engine.mmrRerank(emptyList<ScoredAlbum>(), lambda = 0.5, count = 5, random = Random(1)) { it.genres }
        assertThat(out).isEmpty()
    }
}
