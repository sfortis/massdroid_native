package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.repository.GenreScore
import org.junit.Test

/** Pure tests for genre/URI normalization + affinity in MediaIdentity.kt. */
class MediaIdentityTest {

    @Test
    fun `normalizeGenre lowercases and trims but keeps inner punctuation`() {
        assertThat(normalizeGenre("Rock")).isEqualTo("rock")
        assertThat(normalizeGenre("  POP  ")).isEqualTo("pop")
        assertThat(normalizeGenre("")).isEqualTo("")
        assertThat(normalizeGenre("HIP-HOP")).isEqualTo("hip-hop")
    }

    @Test
    fun `canonicalMediaKey prefers the normalized uri`() {
        assertThat(MediaIdentity.canonicalMediaKey(uri = "library://artist/5"))
            .isEqualTo("library://artist/5")
        assertThat(MediaIdentity.canonicalMediaKey(uri = "  library://artist/5  "))
            .isEqualTo("library://artist/5")
    }

    @Test
    fun `canonicalMediaKey strips fragment and query from the uri`() {
        assertThat(MediaIdentity.canonicalMediaKey(uri = "library://artist/5#frag"))
            .isEqualTo("library://artist/5")
        assertThat(MediaIdentity.canonicalMediaKey(uri = "spotify://x?q=1"))
            .isEqualTo("spotify://x")
    }

    @Test
    fun `canonicalMediaKey falls back to itemId when the uri is blank or unusable`() {
        assertThat(MediaIdentity.canonicalMediaKey(itemId = "id7", uri = "")).isEqualTo("id7")
        assertThat(MediaIdentity.canonicalMediaKey(itemId = "id7", uri = null)).isEqualTo("id7")
        // uri "#frag" normalizes to empty -> itemId path; itemId blank -> null
        assertThat(MediaIdentity.canonicalMediaKey(itemId = "  ", uri = "#frag")).isNull()
    }

    @Test
    fun `canonicalMediaKey returns null when both inputs are empty`() {
        assertThat(MediaIdentity.canonicalMediaKey(itemId = null, uri = null)).isNull()
        assertThat(MediaIdentity.canonicalMediaKey(itemId = "", uri = "")).isNull()
    }

    @Test
    fun `genreAffinity averages the strongest positive genres`() {
        val scores = mapOf("rock" to 4.0, "pop" to 2.0)
        assertThat(genreAffinity(listOf("rock", "pop"), scores)).isEqualTo(3.0)
    }

    @Test
    fun `genreAffinity caps at topN strongest`() {
        val scores = mapOf("a" to 9.0, "b" to 6.0, "c" to 3.0)
        // default topN = 2 -> (9 + 6) / 2, "c" excluded
        assertThat(genreAffinity(listOf("a", "b", "c"), scores)).isEqualTo(7.5)
    }

    @Test
    fun `genreAffinity ignores non-positive scores and is case-insensitive`() {
        assertThat(genreAffinity(listOf("rock"), mapOf("rock" to -5.0))).isEqualTo(0.0)
        assertThat(genreAffinity(emptyList(), emptyMap())).isEqualTo(0.0)
        assertThat(genreAffinity(listOf("ROCK"), mapOf("rock" to 4.0))).isEqualTo(4.0)
    }

    // --- genreKey: Music Assistant's create_safe_string(..., replace_space=True) ---

    @Test
    fun `genreKey merges the spellings found on the real library on 2026-10-05`() {
        val variants = listOf(
            listOf("synthpop", "synth pop", "synth-pop"),
            listOf("singer songwriter", "singer-songwriter"),
            listOf("post punk", "post-punk"),
            listOf("post rock", "post-rock"),
            listOf("darkwave", "dark wave"),
            listOf("lo fi", "lo-fi"),
            listOf("post metal", "post-metal"),
            listOf("post hardcore", "post-hardcore"),
            listOf("avant garde", "avant-garde"),
        )
        for (spellings in variants) {
            assertThat(spellings.map(::genreKey).toSet()).hasSize(1)
        }
        assertThat(genreKey("Synth-Pop")).isEqualTo("synthpop")
        assertThat(genreKey("  Post Punk  ")).isEqualTo("postpunk")
    }

    @Test
    fun `genreKey keeps different genres apart`() {
        // Merging must stop exactly where the server stops: nothing beyond
        // case, spacing, punctuation and accents.
        assertThat(genreKey("post punk")).isNotEqualTo(genreKey("punk"))
        assertThat(genreKey("synthpop")).isNotEqualTo(genreKey("synthwave"))
        assertThat(genreKey("rnb")).isNotEqualTo(genreKey("r&b"))
    }

    @Test
    fun `genreKey strips accents so electro spellings meet`() {
        assertThat(genreKey("électro")).isEqualTo("electro")
        assertThat(genreKey("Électro")).isEqualTo(genreKey("electro"))
        assertThat(genreKey("música popular")).isEqualTo("musicapopular")
    }

    @Test
    fun `genreKey drops symbols the way the server does`() {
        assertThat(genreKey("r&b")).isEqualTo("rb")
        assertThat(genreKey("drum & bass")).isEqualTo(genreKey("drum bass"))
    }

    @Test
    fun `genreKey falls back to the normalized name when nothing ASCII is left`() {
        // Without the fallback every such genre would share the empty key.
        assertThat(genreKey("!!!")).isEqualTo("!!!")
        assertThat(genreKey(" Ραμπέτικο ")).isEqualTo("ραμπέτικο")
        assertThat(genreKey("ραμπέτικο")).isNotEqualTo(genreKey("λαϊκό"))
        assertThat(genreKey("")).isEmpty()
        assertThat(genreKey("   ")).isEmpty()
    }

    @Test
    fun `distinctGenres keeps the first spelling of each genre`() {
        assertThat(distinctGenres(listOf("synth-pop", "house", "synthpop", "Synth Pop")))
            .containsExactly("synth-pop", "house").inOrder()
    }

    // --- canonicalGenreSpellings: which spelling a genre keeps ---

    @Test
    fun `the most used spelling wins`() {
        // Track plus artist rows from the 2026-10-05 database.
        val chosen = canonicalGenreSpellings(
            mapOf("synthpop" to 926 + 183, "synth-pop" to 193 + 123, "synth pop" to 33 + 9)
        )
        assertThat(chosen).containsExactly("synthpop", "synthpop")
    }

    @Test
    fun `a tie goes to the lexically smallest spelling whatever the input order`() {
        val forward = canonicalGenreSpellings(linkedMapOf("post-rock" to 5, "post rock" to 5))
        val backward = canonicalGenreSpellings(linkedMapOf("post rock" to 5, "post-rock" to 5))
        assertThat(forward).containsExactly("postrock", "post rock")
        assertThat(backward).isEqualTo(forward)
    }

    @Test
    fun `genres with one spelling keep it and blanks are ignored`() {
        val chosen = canonicalGenreSpellings(mapOf("house" to 1, "techno" to 0, " " to 9))
        assertThat(chosen).containsExactly("house", "house", "techno", "techno")
    }

    @Test
    fun `scores are found under any spelling of the genre`() {
        // The scores come from the database, the track genres from the server.
        val scores = listOf(GenreScore("synthpop", 4.0), GenreScore("post punk", 2.0)).toScoreMap()
        assertThat(genreAffinity(listOf("synth-pop"), scores)).isEqualTo(4.0)
        assertThat(genreAffinity(listOf("Post-Punk"), scores)).isEqualTo(2.0)
    }

    @Test
    fun `two spellings on one track count as one genre`() {
        val scores = listOf(GenreScore("synthpop", 4.0), GenreScore("house", 2.0)).toScoreMap()
        // Counted twice, synthpop would fill both top-2 slots and give 4.0.
        assertThat(genreAffinity(listOf("synthpop", "synth-pop", "house"), scores)).isEqualTo(3.0)
    }
}
