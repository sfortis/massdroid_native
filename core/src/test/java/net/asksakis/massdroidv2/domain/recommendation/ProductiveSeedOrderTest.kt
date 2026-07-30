package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.repository.SeedTrack
import org.junit.Test

/**
 * Pins the rule that seeds able to produce candidates are asked first.
 *
 * Why it matters: Music Assistant answers `similar_artists` only for library
 * items and returns nothing for provider items (verified live). On a real
 * history only 524 of 2050 seed-eligible artists are library ones, so a cluster
 * chosen purely on genre averages ~2 productive seeds out of 8, and one unlucky
 * cluster produced an 8-track mix against a target of 33.
 */
class ProductiveSeedOrderTest {

    private fun seed(name: String, uri: String) =
        SeedTrack(
            trackUri = "library://track/$name",
            trackName = name,
            artistName = name,
            lastPlayedAt = 0L,
            score = 0.5,
            artistUri = uri
        )

    @Test
    fun `library seeds come before provider seeds`() {
        val ordered = productiveFirst(
            listOf(
                seed("deezer-one", "deezer--x://artist/1"),
                seed("lib-one", "library://artist/1"),
                seed("deezer-two", "deezer--x://artist/2"),
                seed("lib-two", "library://artist/2")
            )
        )
        assertThat(ordered.map { it.artistName })
            .containsExactly("lib-one", "lib-two", "deezer-one", "deezer-two")
            .inOrder()
    }

    @Test
    fun `relative order within each group is preserved`() {
        // The caller has already ranked these by Strictness, so the partition
        // must not reshuffle them.
        val ordered = productiveFirst(
            listOf(
                seed("lib-first", "library://artist/1"),
                seed("lib-second", "library://artist/2"),
                seed("lib-third", "library://artist/3")
            )
        )
        assertThat(ordered.map { it.artistName })
            .containsExactly("lib-first", "lib-second", "lib-third")
            .inOrder()
    }

    @Test
    fun `a seed with no artist uri is treated as unproductive`() {
        val ordered = productiveFirst(
            listOf(seed("no-uri", ""), seed("lib", "library://artist/1"))
        )
        assertThat(ordered.first().artistName).isEqualTo("lib")
    }

    @Test
    fun `an all-provider cluster is returned untouched`() {
        val seeds = listOf(seed("a", "deezer--x://artist/1"), seed("b", "deezer--x://artist/2"))
        assertThat(productiveFirst(seeds)).isEqualTo(seeds)
    }
}
