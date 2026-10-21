package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * Pins which tracks close a mix.
 *
 * Music Assistant keeps the last ten enqueued items as Autoplay seeds, so the end of a mix
 * decides what plays after it. A discovery pick in those slots once seeded an evening of
 * artists the listener kept blocking. The tail therefore takes the listener's own loved tracks
 * first, then the best on-family candidates, and never an off-family one while others exist.
 */
class AutoplaySeedTailTest {

    private data class Item(
        val id: String,
        val artist: String,
        val loved: Boolean = false,
        val verified: Boolean = true,
        val score: Double = 0.5,
    )

    private fun order(mix: List<Item>, tailSize: Int = 3, artistGap: Int = 1, lovedMax: Int = tailSize) =
        orderAutoplaySeedTail(
            mix = mix,
            artistKeyOf = { it.artist },
            isEligible = { it.loved || it.verified },
            isLoved = { it.loved },
            trustOrder = compareByDescending<Item> { it.loved }.thenByDescending { it.score },
            artistGap = artistGap,
            tailSize = tailSize,
            lovedMax = lovedMax,
        )

    /** Fails when an artist returns within [gap] tracks anywhere in [items]. */
    private fun assertArtistGap(items: List<Item>, gap: Int) {
        items.forEachIndexed { i, item ->
            val before = items.subList(maxOf(0, i - gap), i).map { it.artist }
            assertThat(before).doesNotContain(item.artist)
        }
    }

    @Test
    fun `the tail holds at most the loved share, and on-family tracks fill the rest`() {
        val loved = (1..8).map { Item("loved$it", "L$it", loved = true) }
        val fit = (1..6).map { Item("fit$it", "F$it", score = 0.9) }
        val mix = listOf(Item("open", "O")) + loved + fit

        val tail = order(mix, tailSize = 10, lovedMax = 5).takeLast(10)

        assertThat(tail.count { it.loved }).isEqualTo(5)
        assertThat(tail.count { !it.loved }).isEqualTo(5)
    }

    @Test
    fun `a tail of a few artists keeps the mix's artist gap`() {
        // Loved and top-scored tracks often come from a few favourite artists; spaced only
        // against the previous track they would end the mix as A B A B.
        val tail = listOf("A", "A", "A", "A", "B", "B", "B", "C", "C", "C")
            .mapIndexed { i, artist -> Item("t$i", artist, score = 0.9) }
        val body = (1..6).map { Item("b$it", "X$it", verified = false) }
        val mix = listOf(Item("open", "O")) + tail + body

        val ordered = order(mix, tailSize = 10, artistGap = 2)

        assertThat(ordered.takeLast(10)).containsExactlyElementsIn(tail)
        assertArtistGap(ordered, gap = 2)
    }

    @Test
    fun `taking tracks out of the body restores the gap the interleave kept`() {
        // Without the loved tracks between them, A would return after a single track.
        val mix = listOf(
            Item("open", "O"),
            Item("a1", "A", verified = false),
            Item("l", "L", loved = true),
            Item("b1", "B", verified = false),
            Item("m", "M", loved = true),
            Item("a2", "A", verified = false),
            Item("c1", "C", verified = false),
            Item("d1", "D", verified = false),
        )

        val ordered = order(mix, tailSize = 2, artistGap = 2)

        assertThat(ordered.takeLast(2).map { it.id }).containsExactly("l", "m")
        assertArtistGap(ordered, gap = 2)
    }

    @Test
    fun `the opener keeps its place and no track is added or lost`() {
        val mix = listOf(
            Item("open", "A"),
            Item("loved", "B", loved = true),
            Item("x", "C", score = 0.9),
            Item("y", "D", score = 0.1),
            Item("z", "E", verified = false),
        )

        val ordered = order(mix)

        assertThat(ordered.first().id).isEqualTo("open")
        assertThat(ordered).containsExactlyElementsIn(mix)
    }

    @Test
    fun `loved tracks close the mix, then the best on-family ones`() {
        val mix = listOf(
            Item("open", "A"),
            Item("loved1", "B", loved = true),
            Item("high", "C", score = 0.9),
            Item("loved2", "D", loved = true),
            Item("low", "E", score = 0.2),
            Item("mid", "F", score = 0.6),
        )

        val tail = order(mix, tailSize = 3).takeLast(3).map { it.id }

        assertThat(tail).containsExactly("loved1", "high", "loved2").inOrder()
    }

    @Test
    fun `an off-family pick at the end is moved out of the tail`() {
        val mix = listOf(
            Item("open", "A"),
            Item("fit1", "B"),
            Item("fit2", "C"),
            Item("fit3", "D"),
            Item("stranger", "E", verified = false),
        )

        val tail = order(mix, tailSize = 3).takeLast(3).map { it.id }

        assertThat(tail).doesNotContain("stranger")
    }

    @Test
    fun `moving tracks does not put one artist twice in a row`() {
        // Taking "loved" out of the body would leave the two A2 tracks side by side.
        val mix = listOf(
            Item("open", "A1"),
            Item("a2-first", "A2", verified = false),
            Item("loved", "B", loved = true),
            Item("a2-second", "A2", verified = false),
            Item("c", "C", verified = false),
        )

        val ordered = order(mix, tailSize = 1)

        assertThat(ordered.last().id).isEqualTo("loved")
        ordered.zipWithNext().forEach { (a, b) -> assertThat(a.artist).isNotEqualTo(b.artist) }
    }

    @Test
    fun `a mix with nothing eligible keeps its order`() {
        val mix = listOf(
            Item("open", "A"),
            Item("x", "B", verified = false),
            Item("y", "C", verified = false),
        )

        assertThat(order(mix)).isEqualTo(mix)
    }

    @Test
    fun `buildFromCandidates closes the mix with the injected loved track`() {
        fun candidate(id: String, artist: String, score: Double, source: CandidateSource, verified: Boolean = true) =
            CandidateTrack(
                track = Track(
                    itemId = id,
                    provider = "deezer",
                    name = "Track $id",
                    uri = "deezer://track/$id",
                    artistNames = artist,
                    artistUri = "deezer://artist/$artist",
                ),
                score = score,
                verified = verified,
                source = source,
            )
        // The loved track outscores everything, so the near-greedy draw puts it right after the
        // opener. It is unverified only so it cannot BE the opener, which the tail never moves.
        // Without the tail step it would sit at position two, outside the last ten of 21.
        val discovery = (1..20).map { candidate("d$it", "Artist $it", 0.5, CandidateSource.DISCOVERY) }
        val loved = candidate("loved", "Loved Artist", 0.99, CandidateSource.OWN_LOVED, verified = false)

        val mix = MixEngine().buildFromCandidates(discovery + loved, target = 21, randomSeed = 7L, discovery = 0.0)

        assertThat(mix.map { it.uri }).contains("deezer://track/loved")
        assertThat(mix.takeLast(MA_AUTOPLAY_SEED_WINDOW).map { it.uri }).contains("deezer://track/loved")
    }
}
