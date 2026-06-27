package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
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
}
