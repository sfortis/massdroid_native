package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the seed-cluster coherence rules and the loose candidate genre gate that
 * keep a mix in one genre family (a single crowd-mistagged techno track once
 * bridged an indie cluster and filled ~25% of the mix with techno).
 */
class SeedClusterGateTest {

    // --- seedJoinsCluster: one rule, the primary's dominant family ---

    private fun joins(seed: List<String>, primary: List<String>) =
        seedJoinsCluster(seed, primary, dominantFamily(primary))

    @Test
    fun `a seed of the same kind of music joins`() {
        assertThat(joins(listOf("indie", "indie rock", "rock"), listOf("indie rock", "psychedelic")))
            .isTrue()
        assertThat(joins(listOf("house", "deep house"), listOf("deep house", "electronica"))).isTrue()
    }

    @Test
    fun `a seed of another family stays out even sharing a tag`() {
        // Benjamin Damage vs an indie cluster: zero shared tags, foreign family.
        assertThat(joins(listOf("techno", "tech house", "house"), listOf("indie rock", "psychedelic")))
            .isFalse()
        // The real Ronan case: a bossa-cover act must not join a house cluster
        // just because the primary happens to carry a "lounge" side-tag.
        assertThat(joins(listOf("jazz", "chillout"), listOf("house", "chillout", "lounge"))).isFalse()
    }

    @Test
    fun `a side-tag match is not enough, the dominant tag decides`() {
        // Both carry "chillout", but one IS chill and the other IS electronic.
        assertThat(joins(listOf("chillout", "lounge"), listOf("house", "chillout"))).isFalse()
        assertThat(joins(listOf("chillout", "lounge"), listOf("lounge", "chillout"))).isTrue()
    }

    @Test
    fun `an unmapped primary falls back to a shared exact tag`() {
        assertThat(joins(listOf("zeuhl", "lo fi"), listOf("zeuhl"))).isTrue()
        assertThat(joins(listOf("lo fi"), listOf("zeuhl"))).isFalse()
    }

    @Test
    fun `a seed with no genres never joins a mapped cluster`() {
        assertThat(joins(emptyList(), listOf("rock"))).isFalse()
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

    // --- Genre Radio seeds: the artist decides, not a stray track tag ---

    @Test
    fun `a stray track tag cannot seed a genre radio`() {
        // Ist Ist: a post-punk band with one track mistagged "disco" by the provider.
        assertThat(seedMatchesGenre(listOf("post punk", "new wave"), listOf("disco"), "disco")).isFalse()
        // Bicep really is tagged disco at the artist level.
        assertThat(seedMatchesGenre(listOf("house", "disco", "techno"), emptyList(), "disco")).isTrue()
        // With nothing known about the artist, the track's own genres still count.
        assertThat(seedMatchesGenre(emptyList(), listOf("disco"), "disco")).isTrue()
        assertThat(seedMatchesGenre(emptyList(), emptyList(), "disco")).isFalse()
    }

    // --- dominantFamily: an artist is judged by their heaviest tag ---

    @Test
    fun `dominant family is the first mapped tag`() {
        // Real cached tags, weight-ordered as Last.fm returns them.
        assertThat(dominantFamily(listOf("trance", "electronic", "ambient"))).isEqualTo("electronic")
        assertThat(dominantFamily(listOf("darkwave", "electronic", "synthpop"))).isEqualTo("goth")
        assertThat(dominantFamily(listOf("lounge", "chillout", "jazz"))).isEqualTo("chill")
    }

    @Test
    fun `an alphabetical genre set is judged by its best-represented family`() {
        // IAMX as the DB stores it (a set, so alphabetical). Reading the first
        // tag made it ROCK on the strength of "alternative"; four of its six
        // tags are electronic, which is also what Last.fm's weighted tags say.
        val dbSet = listOf("alternative", "electronic", "house", "psychedelic", "synthpop", "trance")
        assertThat(dominantFamily(dbSet)).isEqualTo("rock")
        assertThat(dominantFamily(orderByFamilyFrequency(dbSet))).isEqualTo("electronic")
        // A weight-ordered Last.fm list must survive the reordering unchanged
        // when its first tag already belongs to the majority family.
        assertThat(orderByFamilyFrequency(listOf("house", "deep house", "jazz")).first()).isEqualTo("house")
    }

    @Test
    fun `dominant family skips leading unmapped tags`() {
        assertThat(dominantFamily(listOf("instrumental", "indie rock"))).isEqualTo("rock")
        assertThat(dominantFamily(listOf("lo fi", "instrumental"))).isNull()
    }

    // --- the gate judges on the dominant tag, not any tag (real bleed cases) ---

    private fun gateAgainst(envelope: Set<String>, candidate: List<String>) = genreGatePasses(
        candidate,
        envelopeFamilies = genreFamilies(envelope),
        envelopeTokens = genreTokens(envelope)
    )

    private val loungeEnvelope = setOf("lounge", "chillout", "jazz", "swing")
    private val swingEnvelope = setOf("jazz", "pop", "swing")

    @Test
    fun `a trance artist no longer enters a lounge mix on their ambient tag`() {
        // Robert Miles - Fable, item #5 of a bossa/lounge mix.
        assertThat(gateAgainst(loungeEnvelope, listOf("trance", "electronic", "ambient"))).isFalse()
        // Schiller and Ochre arrived the same way.
        assertThat(gateAgainst(loungeEnvelope, listOf("electronic", "chillout", "ambient"))).isFalse()
        assertThat(gateAgainst(loungeEnvelope, listOf("idm", "ambient", "electronic"))).isFalse()
    }

    @Test
    fun `genuine lounge and jazz acts still pass that envelope`() {
        assertThat(gateAgainst(loungeEnvelope, listOf("lounge", "chillout", "jazz"))).isTrue()
        assertThat(gateAgainst(loungeEnvelope, listOf("jazz", "swing"))).isTrue()
        assertThat(gateAgainst(loungeEnvelope, listOf("chillout", "lounge", "downtempo"))).isTrue()
    }

    @Test
    fun `darkwave and synthpop acts no longer enter a vintage jazz-pop mix`() {
        // NNHMN entered on "synthpop", Brendan Perry on "ambient".
        assertThat(gateAgainst(swingEnvelope, listOf("darkwave", "electronic", "synthpop"))).isFalse()
        assertThat(gateAgainst(swingEnvelope, listOf("ethereal", "ambient", "singer songwriter"))).isFalse()
        // Synth pop now belongs to the electronic family, so it is foreign to a
        // jazz/pop cluster (Alphaville, Das Beat, Sin Cos Tan, MOTHERMARY).
        assertThat(gateAgainst(swingEnvelope, listOf("synthpop", "new wave", "pop"))).isFalse()
        assertThat(gateAgainst(swingEnvelope, listOf("synthpop", "electropop", "electronic"))).isFalse()
    }

    @Test
    fun `synth pop sits with electronic, not pop`() {
        assertThat(genreFamilies(listOf("synthpop", "synth pop", "electropop")))
            .containsExactly("electronic")
        assertThat(genreFamilies(listOf("pop", "disco", "ballad"))).containsExactly("pop")
    }

    // --- the mix answers to the primary's family, not the seed union ---

    @Test
    fun `a single seed side-tag no longer opens a foreign family`() {
        // The real lounge cluster: seven pure lounge/jazz seeds plus Klub Rider
        // [lounge, electronic, downtempo]. The union of every seed tag contains
        // "electronic"; the primary is simply chill.
        val seedTags = listOf(
            listOf("lounge"),
            listOf("easy listening", "jazz", "lounge"),
            listOf("chillout", "jazz", "lounge"),
            listOf("lounge", "electronic", "downtempo"),
            listOf("lounge", "chillout"),
        )
        val union = seedTags.flatten().toSet()
        assertThat(genreFamilies(union)).contains("electronic")

        val core = setOfNotNull(dominantFamily(seedTags.first()))
        assertThat(core).containsExactly("chill")
        // Robert Miles stays out of the mix he actually landed in.
        assertThat(
            genreGatePasses(
                listOf("trance", "electronic", "ambient"),
                withAdjacentFamilies(core),
                genreTokens(union)
            )
        ).isFalse()
    }

    @Test
    fun `adjacent families keep genuinely close acts in`() {
        val core = setOf("jazz")
        // Lake Street Dive and Paloma Faith are retro-soul, at home in a swing mix.
        assertThat(genreGatePasses(listOf("soul", "jazz", "swing"), withAdjacentFamilies(core), emptySet()))
            .isTrue()
        assertThat(genreGatePasses(listOf("soul", "pop", "alternative"), withAdjacentFamilies(core), emptySet()))
            .isTrue()
        // Adjacency is narrow: chill does NOT reach electronic, which is the leak.
        assertThat(withAdjacentFamilies(setOf("chill"))).containsExactly("chill")
        assertThat(withAdjacentFamilies(emptySet())).isEmpty()
    }

    @Test
    fun `an empty envelope gates nothing (new user, no genre data)`() {
        // A cluster with no genre identity of its own cannot judge anything, so
        // a library with no tags yet is never filtered down to nothing.
        assertThat(genreGatePasses(listOf("techno"), emptySet(), emptySet())).isTrue()
        assertThat(genreGatePasses(listOf("anything at all"), emptySet(), emptySet())).isTrue()
    }

    @Test
    fun `a candidate with no genres at all shows no evidence of fit`() {
        // The pure rule is strict, which is what keeps Genre Radio's injection
        // free of untagged filler. The DISCOVERY path never asks: an artist with
        // no cached tags is admitted before the gate is consulted, because the
        // absence of tags there means "not fetched yet", not "no genre".
        assertThat(genreGatePasses(emptyList(), setOf("jazz"), setOf("jazz"))).isFalse()
    }

    @Test
    fun `an indie rock favourite cannot be injected into an electronic mix`() {
        // Arcade Fire (indie rock, indie, alternative) reached an electronic mix
        // through its noisy TRACK genres; artist tags keep it out.
        val electronicEnvelope = setOf("electronic", "electronica", "electropop")
        assertThat(gateAgainst(electronicEnvelope, listOf("indie rock", "indie", "alternative"))).isFalse()
        assertThat(gateAgainst(electronicEnvelope, listOf("electronic", "indie", "psychedelic"))).isTrue()
    }
}
