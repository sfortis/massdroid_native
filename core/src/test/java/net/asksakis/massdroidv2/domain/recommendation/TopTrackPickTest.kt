package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test
import kotlin.random.Random

/**
 * Pins how the artist route draws an artist's tracks. Taking the first two top
 * tracks every time filled mixes with the best-known hits.
 */
class TopTrackPickTest {

    private val tracks = (1..15).map { i ->
        Track(itemId = "$i", provider = "deezer", name = "Track $i", uri = "deezer://track/$i")
    }

    @Test
    fun `the pick stays inside the first ten`() {
        repeat(200) { seed ->
            val picked = pickTopTracks(tracks, 2, Random(seed))
            assertThat(picked).hasSize(2)
            assertThat(tracks.take(10)).containsAtLeastElementsIn(picked)
        }
    }

    @Test
    fun `the pick changes with the seed and repeats with the same seed`() {
        val picks = (0L until 20L).map { pickTopTracks(tracks, 2, Random(it)) }.toSet()
        assertThat(picks.size).isGreaterThan(1)
        assertThat(pickTopTracks(tracks, 2, Random(42))).isEqualTo(pickTopTracks(tracks, 2, Random(42)))
    }

    @Test
    fun `the picked tracks keep the provider's order`() {
        repeat(50) { seed ->
            val picked = pickTopTracks(tracks, 2, Random(seed))
            assertThat(tracks.indexOf(picked[0])).isLessThan(tracks.indexOf(picked[1]))
        }
    }

    @Test
    fun `a short answer is taken whole`() {
        assertThat(pickTopTracks(tracks.take(2), 2, Random(1))).isEqualTo(tracks.take(2))
        assertThat(pickTopTracks(tracks.take(1), 2, Random(1))).isEqualTo(tracks.take(1))
    }
}
