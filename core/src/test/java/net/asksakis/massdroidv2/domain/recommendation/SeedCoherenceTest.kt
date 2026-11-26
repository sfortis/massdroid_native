package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins which family-only seeds may join a cluster. The broad family let
 * Nachtmystium (black metal) into a cluster anchored on Toundra (post-rock,
 * post-metal), and the mix filled with black metal the listener skipped.
 */
class SeedCoherenceTest {

    private val toundra = genreKeys(listOf("instrumental", "post rock", "post-hardcore", "post-metal"))

    // Stored spellings differ from the MusicBrainz ones; the rule compares keys.
    private val adjacency = adjacencyByKey(
        mapOf(
            "Post-Metal" to setOf("Sludge Metal", "Post-Rock"),
            "Sludge Metal" to setOf("Post-Metal"),
            "Black Metal" to setOf("Atmospheric Black Metal")
        )
    )

    @Test
    fun `a seed with no shared or adjacent genre stays out`() {
        val nachtmystium = listOf("black metal", "atmospheric black metal", "melodic black metal", "experimental")
        assertThat(seedNearPrimaryGenre(nachtmystium, toundra, adjacency)).isFalse()
    }

    @Test
    fun `a seed with a genre adjacent to the primary's joins`() {
        assertThat(seedNearPrimaryGenre(listOf("sludge metal", "doom metal"), toundra, adjacency)).isTrue()
    }

    @Test
    fun `a seed sharing an exact genre joins whatever the spelling`() {
        assertThat(seedNearPrimaryGenre(listOf("Post Metal"), toundra, emptyMap())).isTrue()
    }

    @Test
    fun `without an adjacency map only an exact genre lets a seed in`() {
        assertThat(seedNearPrimaryGenre(listOf("sludge metal"), toundra, emptyMap())).isFalse()
    }
}
