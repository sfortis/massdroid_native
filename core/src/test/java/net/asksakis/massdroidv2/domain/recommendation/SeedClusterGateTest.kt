package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the seed-cluster coherence rules and the loose candidate genre gate that
 * keep a mix in one genre family (a single crowd-mistagged techno track once
 * bridged an indie cluster and filled ~25% of the mix with techno).
 */
class SeedClusterGateTest {

    // --- clusterOverlapSatisfied: 1 shared artist tag + family veto ---

    @Test
    fun `zero shared artist tags never joins`() {
        // Benjamin Damage (clean artist tags) vs the Orions Belte cluster.
        val seed = setOf("techno", "tech house", "house")
        val primary = setOf("indie rock", "psychedelic", "psychedelic rock")
        assertThat(clusterOverlapSatisfied(seed, primary)).isFalse()
    }

    @Test
    fun `one shared same-family tag joins (real The Panics case)`() {
        val seed = setOf("indie", "indie rock", "rock")
        val primary = setOf("indie rock", "psychedelic", "psychedelic rock")
        assertThat(clusterOverlapSatisfied(seed, primary)).isTrue()
    }

    @Test
    fun `shared tag with disjoint known families is vetoed`() {
        // A noisy shared tag cannot bridge two known-different worlds.
        val seed = setOf("techno", "house", "unknown bridge tag")
        val primary = setOf("indie rock", "unknown bridge tag")
        assertThat(clusterOverlapSatisfied(seed, primary)).isFalse()
    }

    @Test
    fun `shared tag passes when one side has no mapped family`() {
        val seed = setOf("zeuhl", "lo fi")
        val primary = setOf("indie rock", "zeuhl")
        assertThat(clusterOverlapSatisfied(seed, primary)).isTrue()
    }

    @Test
    fun `empty genre sets never join`() {
        assertThat(clusterOverlapSatisfied(emptySet(), setOf("rock"))).isFalse()
        assertThat(clusterOverlapSatisfied(setOf("rock"), emptySet())).isFalse()
    }

    // --- genreTokens ---

    @Test
    fun `tokens split multi-word genres and drop short noise and connectors`() {
        assertThat(genreTokens(listOf("indie rock", "drum and bass")))
            .containsExactly("indie", "rock", "drum", "bass")
    }

    // --- genresOverlapLoose: permissive fallback for unmapped tags ---

    @Test
    fun `sub-genre passes via shared token`() {
        val envelope = genreTokens(setOf("rock"))
        assertThat(genresOverlapLoose(listOf("indie rock"), envelope)).isTrue()
    }

    @Test
    fun `containment matches electro against electronic`() {
        val envelope = genreTokens(setOf("electronic"))
        assertThat(genresOverlapLoose(listOf("electro"), envelope)).isTrue()
    }

    @Test
    fun `empty envelope passes everything`() {
        assertThat(genresOverlapLoose(listOf("techno"), emptySet())).isTrue()
    }

    // --- genreFamilies: static mapping over the Last.fm whitelist ---

    @Test
    fun `families map deterministic worlds`() {
        assertThat(genreFamilies(listOf("techno", "tech house", "house"))).containsExactly("electronic")
        assertThat(genreFamilies(listOf("indie rock", "psychedelic", "shoegaze"))).containsExactly("rock")
        assertThat(genreFamilies(listOf("punk rock", "hardcore"))).containsExactly("punk")
    }

    @Test
    fun `unmapped tags contribute no family`() {
        assertThat(genreFamilies(listOf("zeuhl", "lo fi"))).isEmpty()
    }

    @Test
    fun `unknown multi-word genres resolve via their last word (public-library generalization)`() {
        // Provider/ID3 genres outside the Last.fm whitelist must still land in
        // the right family for any user's library.
        assertThat(genreFamilies(listOf("deep tech house"))).containsExactly("electronic")
        assertThat(genreFamilies(listOf("greek rock"))).containsExactly("rock")
        assertThat(genreFamilies(listOf("brutal slam metal"))).containsExactly("metal")
        assertThat(genreFamilies(listOf("garage punk"))).containsExactly("punk")
    }

    @Test
    fun `glued single-word coinages resolve via suffix`() {
        assertThat(genreFamilies(listOf("hyperpop"))).containsExactly("pop")
        assertThat(genreFamilies(listOf("darksynth"))).isEmpty() // ambiguous, stays unmapped
        assertThat(genreFamilies(listOf("psychobilly"))).containsExactly("rock")
    }

    @Test
    fun `mood and format tags stay family-neutral`() {
        assertThat(genreFamilies(listOf("instrumental", "soundtrack", "lo fi"))).isEmpty()
    }

    // --- genreGatePasses: family-first, token fallback ---

    private val indieEnvelope = setOf("indie", "indie rock", "psychedelic", "psychedelic rock", "alternative")

    private fun gate(candidate: List<String>) = genreGatePasses(
        candidate,
        envelopeFamilies = genreFamilies(indieEnvelope),
        envelopeTokens = genreTokens(indieEnvelope)
    )

    @Test
    fun `techno candidate is dropped from an indie envelope by family`() {
        // The run-5 contamination: Koelsch/Recondite similars in an indie mix.
        assertThat(gate(listOf("techno", "electronic", "house"))).isFalse()
    }

    @Test
    fun `psytrance is dropped despite the shared psychedelic word-stem`() {
        assertThat(gate(listOf("psytrance", "trance"))).isFalse()
    }

    @Test
    fun `same-family sub-genre passes even with zero exact overlap`() {
        assertThat(gate(listOf("britpop", "garage rock"))).isTrue()
    }

    @Test
    fun `unmapped candidate falls back to token overlap`() {
        // "zeuhl rock" is not in the family map; the "rock" token lets it pass.
        assertThat(gate(listOf("zeuhl rock"))).isTrue()
        // Fully unknown and token-disjoint stays out.
        assertThat(gate(listOf("zeuhl"))).isFalse()
    }
}
