package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins what a mix is called.
 *
 * The name is the only description the listener gets, and a majority vote made
 * it useless: umbrella tags lead a great many artists, so "alternative" won in
 * almost any rock-ish cluster. Real clusters from a device - progressive rock
 * around Gazpacho, post-rock around Pg.lost - were both announced as
 * "alternative", and 11 of 23 mixes landed in the rock family.
 */
class MixLabelTest {

    @Test
    fun `the primary seed names the mix`() {
        val label = mixLabel(
            seedGenres = listOf(
                listOf("progressive rock", "art rock", "crossover prog"), // primary
                listOf("alternative", "indie"),
                listOf("alternative", "rock"),
                listOf("indie rock")
            ),
            family = "rock"
        )
        assertThat(label).isEqualTo("progressive rock")
    }

    @Test
    fun `an umbrella primary defers to what the rest agrees on`() {
        val label = mixLabel(
            seedGenres = listOf(
                listOf("alternative"), // primary, too broad to name a mix
                listOf("shoegaze", "dream pop"),
                listOf("shoegaze"),
                listOf("indie")
            ),
            family = "rock"
        )
        assertThat(label).isEqualTo("shoegaze")
    }

    @Test
    fun `umbrella tags never win the vote`() {
        // Three "alternative" against two "post punk": the specific one wins.
        val label = mixLabel(
            seedGenres = listOf(
                listOf("alternative"),
                listOf("alternative"),
                listOf("alternative"),
                listOf("post punk"),
                listOf("post punk")
            ),
            family = "rock"
        )
        assertThat(label).isEqualTo("post punk")
    }

    @Test
    fun `one specific seed is not enough to name the whole mix`() {
        // Below MIX_LABEL_MIN_AGREEMENT: falls back rather than naming the mix
        // after a single outlier.
        val label = mixLabel(
            seedGenres = listOf(
                listOf("alternative"),
                listOf("bossa nova"),
                listOf("indie")
            ),
            family = "rock"
        )
        assertThat(label).isEqualTo("alternative")
    }

    @Test
    fun `with nothing specific anywhere the family names it`() {
        val label = mixLabel(
            seedGenres = listOf(listOf("unmapped tag"), listOf("another unmapped")),
            family = "rock"
        )
        assertThat(label).isEqualTo("rock")
    }

    @Test
    fun `no genres at all yields the family`() {
        assertThat(mixLabel(seedGenres = emptyList(), family = "chill")).isEqualTo("chill")
    }

    @Test
    fun `no genres and no family yields nothing rather than a guess`() {
        assertThat(mixLabel(seedGenres = emptyList(), family = null)).isNull()
    }

    @Test
    fun `ties resolve the same way every time`() {
        val seeds = listOf(
            listOf("alternative"),
            listOf("deep house"),
            listOf("deep house"),
            listOf("nu disco"),
            listOf("nu disco")
        )
        val first = mixLabel(seeds, "electronic")
        repeat(5) { assertThat(mixLabel(seeds, "electronic")).isEqualTo(first) }
        assertThat(first).isEqualTo("deep house")
    }
}
