package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import net.asksakis.massdroidv2.domain.model.Artist
import org.junit.Test

/**
 * Pins that the places where genre names from different sources meet treat every
 * spelling of a genre as that genre, the way Music Assistant does.
 *
 * On a real library on 2026-10-05, "synthpop", "synth-pop" and "synth pop" were
 * three Genre Radio tiles with three artist pools, and the same split existed for
 * post punk, post rock, lo fi and six more genres.
 */
class GenreSpellingIdentityTest {

    private fun artist(id: Int, vararg genres: String) = Artist(
        itemId = "$id",
        provider = "library",
        name = "Artist $id",
        uri = "library://artist/$id",
        genres = genres.toList()
    )

    private val loader = DiscoverContentLoader(mockk(relaxed = true), mockk(relaxed = true))

    private val historyArtist = artist(9)

    private fun build() = loader.buildGenreData(
        artists = listOf(
            artist(1, "Synth-Pop"),
            artist(2, "synth pop", "darkwave"),
            artist(3, "post-punk"),
            artist(4, "post-punk"),
            artist(5, "post punk"),
        ),
        // The stored spelling, as getGenreArtistMap returns it.
        historyGenreArtists = mapOf("synthpop" to listOf(historyArtist.uri)),
        artistByUri = mapOf(historyArtist.uri to historyArtist)
    )

    @Test
    fun `one tile per genre, named with one spelling`() {
        val (items, _, _) = build()

        assertThat(items.map { it.name }).containsExactly("synthpop", "post-punk", "darkwave")
    }

    @Test
    fun `the stored spelling names the tile, else the spelling most artists carry`() {
        val (items, _, _) = build()
        val byName = items.associateBy { it.name }

        // "synthpop" only exists in the history, yet it names the provider artists' tile.
        assertThat(byName.getValue("synthpop").count).isEqualTo(3)
        // Two artists say "post-punk", one says "post punk".
        assertThat(byName.getValue("post-punk").count).isEqualTo(3)
    }

    @Test
    fun `the artist pools are found under any spelling`() {
        val (_, genreArtists, strictGenreArtists) = build()

        assertThat(genreArtists[genreKey("Synth Pop")])
            .containsExactly("library://artist/1", "library://artist/2", "library://artist/9")
        assertThat(strictGenreArtists[genreKey("post punk")])
            .containsExactly("library://artist/3", "library://artist/4", "library://artist/5")
    }

    @Test
    fun `a Genre Radio seed matches whatever spelling the request used`() {
        // The seed's genres are stored names, the request may come from a tile
        // cached before the merge or a car browse id.
        assertThat(seedMatchesGenre(listOf("synthpop"), emptyList(), "Synth-Pop")).isTrue()
        assertThat(seedMatchesGenre(listOf("post punk"), emptyList(), "postpunk")).isTrue()
        assertThat(seedMatchesGenre(listOf("post punk"), emptyList(), "punk")).isFalse()
    }

    @Test
    fun `a glued spelling lands in the same family as the spaced one`() {
        // The glued-suffix guess used to file "postpunk" under punk, while the
        // curated entry for "post punk" is rock.
        assertThat(dominantFamily(listOf("postpunk"))).isEqualTo(dominantFamily(listOf("post punk")))
        assertThat(dominantFamily(listOf("postpunk"))).isEqualTo("rock")
        assertThat(dominantFamily(listOf("singer-songwriter"))).isEqualTo("folk")
        assertThat(dominantFamily(listOf("Avant-Garde"))).isEqualTo("experimental")
    }

    @Test
    fun `a cluster without a family joins on a shared genre in any spelling`() {
        assertThat(seedJoinsCluster(listOf("lo-fi"), listOf("lo fi"), primaryFamily = null)).isTrue()
        assertThat(seedJoinsCluster(listOf("hi fi"), listOf("lo fi"), primaryFamily = null)).isFalse()
    }

    @Test
    fun `two spellings of one genre are two votes for the mix name`() {
        // One seed's genres came from MusicBrainz, the other's from the database.
        val seeds = listOf(listOf("alternative"), listOf("post-punk"), listOf("post punk"))

        assertThat(mixLabel(seeds, family = "rock")).isEqualTo("post punk")
    }
}
