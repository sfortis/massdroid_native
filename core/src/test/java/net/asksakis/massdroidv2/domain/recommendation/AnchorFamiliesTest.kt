package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.repository.GenrePlayRow
import org.junit.Test

/**
 * Pins which families may anchor a whole mix.
 *
 * The failure this guards: a listener whose 365-day history was 0.47% hip hop got a
 * full hip hop mix, because those few plays existed at all only as residue of
 * earlier mixes and the Variety rotation steered into the "freshest" family. The
 * floor keeps whole-mix anchoring to families with a real share of the listening,
 * while the organic door lets a genuinely new taste through within days.
 */
class AnchorFamiliesTest {

    private var next = 0
    private fun rows(family: String, plays: Int, tracks: Int = 1): List<GenrePlayRow> {
        val genre = when (family) {
            "rock" -> "indie rock"; "electronic" -> "techno"; "jazz" -> "jazz"
            "hip hop" -> "hip hop"; else -> family
        }
        return (1..tracks).map { GenrePlayRow("t${next++}", genre, plays / tracks) }
    }

    @Test
    fun `a fringe family cannot anchor, a real one can`() {
        // 0.5% hip hop, the measured contamination level; the floor is 1%.
        val history = rows("rock", 905) + rows("jazz", 90) + rows("hip hop", 5)

        val allowed = anchorFamilies(history, emptyList(), minShare = 0.01, organicDoorPlays = 5)

        assertThat(allowed).containsExactly("rock", "jazz")
    }

    @Test
    fun `a side tag does not inflate a family past the floor`() {
        // Many rock tracks carry an "experimental" side tag. Counting tags would
        // hand experimental their plays; the majority vote per track must not.
        val sideTagged = (1..50).flatMap {
            listOf(
                GenrePlayRow("t${next}", "indie rock", 10),
                GenrePlayRow("t${next}", "rock", 10),
                GenrePlayRow("t${next++}", "experimental", 10)
            )
        }
        val allowed = anchorFamilies(sideTagged, emptyList(), minShare = 0.01, organicDoorPlays = 5)

        assertThat(allowed).containsExactly("rock")
    }

    @Test
    fun `the organic door admits a new taste the share floor would refuse`() {
        val history = rows("rock", 995) + rows("jazz", 5)
        val organic = rows("jazz", 5)

        val allowed = anchorFamilies(history, organic, minShare = 0.01, organicDoorPlays = 5)

        assertThat(allowed).contains("jazz")
    }

    @Test
    fun `mix-served plays cannot open the organic door`() {
        // The organic rows carry only origin='organic' plays; a family whose plays
        // are all generated simply never appears there.
        val history = rows("rock", 995) + rows("hip hop", 5)

        val allowed = anchorFamilies(history, emptyList(), minShare = 0.01, organicDoorPlays = 5)

        assertThat(allowed).doesNotContain("hip hop")
    }

    @Test
    fun `an empty history judges nothing`() {
        assertThat(anchorFamilies(emptyList(), emptyList(), 0.01, 5)).isEmpty()
    }
}
