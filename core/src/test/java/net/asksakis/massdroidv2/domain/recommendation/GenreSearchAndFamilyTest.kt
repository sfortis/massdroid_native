package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the two genre gaps left after the schema 21 spelling merge: the library search
 * compared raw names with SQL `LIKE`, so "synth pop" missed the stored "synthpop", and the
 * Discover family rules matched " pop" as a word, so "synthpop" fell out of the pop family
 * that "synth pop" belonged to.
 */
class GenreSearchAndFamilyTest {

    private val stored = listOf("synthpop", "post rock", "rock", "hip hop", "trip-hop", "electronic")

    @Test
    fun `a search finds a stored genre whatever its spacing or hyphens`() {
        assertThat(genreNamesMatching("synth pop", stored)).containsExactly("synthpop")
        assertThat(genreNamesMatching("Synth-Pop", stored)).containsExactly("synthpop")
        assertThat(genreNamesMatching("hiphop", stored)).containsExactly("hip hop")
    }

    @Test
    fun `a search is still a substring match`() {
        assertThat(genreNamesMatching("rock", stored)).containsExactly("post rock", "rock")
        assertThat(genreNamesMatching("hop", stored)).containsExactly("hip hop", "trip-hop")
    }

    @Test
    fun `a query with no letters or digits finds nothing instead of everything`() {
        assertThat(genreNamesMatching(" - ", stored)).isEmpty()
    }

    @Test
    fun `items are matched by their name and keep their order`() {
        val serverGenres = listOf(41 to "Punk", 33 to "Metal", 47 to "Rock", 30 to "Heavy Metal")
        assertThat(genresMatching("METAL", serverGenres) { it.second }.map { it.first })
            .containsExactly(33, 30).inOrder()
    }

    @Test
    fun `glued, spaced and hyphenated spellings land in the same family`() {
        assertThat(genreFamily("synthpop")).isEqualTo("pop")
        assertThat(genreFamily("synth pop")).isEqualTo("pop")
        assertThat(genreFamily("synth-pop")).isEqualTo("pop")
        assertThat(genreFamily("postrock")).isEqualTo("rock")
        assertThat(genreFamily("post-rock")).isEqualTo("rock")
        assertThat(genreFamily("nu metal")).isEqualTo("metal")
    }

    @Test
    fun `the curated sets and the fallback keep their families`() {
        assertThat(genreFamily("hip hop")).isEqualTo("hip-hop")
        assertThat(genreFamily("drum and bass")).isEqualTo("electronic")
        assertThat(genreFamily("singer-songwriter")).isEqualTo("folk")
        assertThat(genreFamily("bebop")).isEqualTo("jazz")
        assertThat(genreFamily("indie")).isEqualTo("indie")
        assertThat(genreFamily("post-punk")).isEqualTo(genreFamily("post punk"))
    }
}
