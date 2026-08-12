package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * Pins the three-way family judgement and the route merge that replaced the
 * all-or-nothing family gate.
 *
 * The gate used to drop every candidate whose family disagreed with the mix, and
 * that is what let unrelated tracks IN rather than keeping them out. Measured over
 * 16 real clusters, half had fewer than 10 same-family candidates for 33 slots and
 * one had none at all: an `industrial` seed's 70 similar artists shared its family
 * zero times, and a `jazz` seed's 99 similars resolved 45 to chill and 25 to
 * electronic against 8 to jazz. Those neighbourhoods are right and the coarse label
 * is what disagrees. A starved cluster then filled from the OTHER seeds'
 * neighbourhoods, which share nothing with it but that same coarse label.
 *
 * So a describable disagreement is now demoted, and only the undescribable is
 * dropped - the case the measurements actually condemn (22% of those were ever
 * played, against 63% of describable ones at the same similarity rank).
 */
class FamilyMatchTest {

    private val jazzMix = setOf("jazz")

    @Test
    fun `a candidate of the mix's own family is ON`() {
        assertThat(classifyFamily(listOf("jazz", "nu jazz"), jazzMix)).isEqualTo(FamilyMatch.ON)
        assertThat(classifyFamily(listOf("acid jazz"), jazzMix)).isEqualTo(FamilyMatch.ON)
    }

    @Test
    fun `a describable disagreement is OFF, not dropped`() {
        // The measured Submotion Orchestra case: the server's own neighbours for a
        // jazz seed resolve to chill and electronic. They are the right
        // neighbourhood; keeping them behind the on-family ones is the point.
        assertThat(classifyFamily(listOf("chillout", "downtempo"), jazzMix)).isEqualTo(FamilyMatch.OFF)
        assertThat(classifyFamily(listOf("techno"), jazzMix)).isEqualTo(FamilyMatch.OFF)
    }

    @Test
    fun `a candidate we cannot describe stays UNKNOWN`() {
        assertThat(classifyFamily(emptyList(), jazzMix)).isEqualTo(FamilyMatch.UNKNOWN)
        // Nationality and format tags map to no family at all.
        assertThat(classifyFamily(listOf("estados unidos", "instrumental"), jazzMix))
            .isEqualTo(FamilyMatch.UNKNOWN)
    }

    @Test
    fun `an unanchored mix has nothing to disagree with`() {
        assertThat(classifyFamily(listOf("techno"), emptySet())).isEqualTo(FamilyMatch.ON)
        assertThat(classifyFamily(emptyList(), emptySet())).isEqualTo(FamilyMatch.ON)
    }

    @Test
    fun `the dominant tag decides, not the whole set`() {
        // Robert Miles `trance, electronic, ambient` must not read as chill through
        // its weakest tag; the same rule the seed cluster gate uses.
        assertThat(classifyFamily(listOf("trance", "electronic", "ambient"), setOf("chill")))
            .isEqualTo(FamilyMatch.OFF)
        assertThat(classifyFamily(listOf("ambient", "trance"), setOf("chill")))
            .isEqualTo(FamilyMatch.ON)
    }

    // --- mergeRoutes ---

    private fun candidate(uri: String, score: Double, verified: Boolean = true) = CandidateTrack(
        track = Track(
            itemId = uri.substringAfterLast('/'),
            provider = "deezer",
            name = "Track $uri",
            uri = uri,
            artistNames = "Artist"
        ),
        score = score,
        verified = verified
    )

    @Test
    fun `both routes contribute and duplicates keep the better score`() {
        val artists = listOf(candidate("deezer://track/1", 0.9), candidate("deezer://track/2", 0.4))
        val tracks = listOf(candidate("deezer://track/2", 0.7), candidate("deezer://track/3", 0.5))
        val merged = mergeRoutes(artists, tracks)

        assertThat(merged.map { it.track.uri })
            .containsExactly("deezer://track/1", "deezer://track/2", "deezer://track/3")
        assertThat(merged.first { it.track.uri == "deezer://track/2" }.score).isEqualTo(0.7)
    }

    @Test
    fun `a weaker duplicate never overwrites the stronger one`() {
        val artists = listOf(candidate("deezer://track/1", 0.9, verified = true))
        val tracks = listOf(candidate("deezer://track/1", 0.2, verified = false))
        val merged = mergeRoutes(artists, tracks)

        assertThat(merged).hasSize(1)
        assertThat(merged.single().score).isEqualTo(0.9)
        // The stronger entry is kept whole, so its opener eligibility survives too.
        assertThat(merged.single().verified).isTrue()
    }

    @Test
    fun `either route alone is passed through untouched`() {
        val only = listOf(candidate("deezer://track/1", 0.5))
        assertThat(mergeRoutes(only, emptyList())).isEqualTo(only)
        assertThat(mergeRoutes(emptyList(), only)).isEqualTo(only)
        assertThat(mergeRoutes(emptyList(), emptyList())).isEmpty()
    }
}
