package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * Pins which track a mix OPENS with.
 *
 * The genre gate deliberately keeps candidates it cannot describe, because
 * dropping the unknown makes whole scenes invisible. That is the right call for
 * the body of a mix and the wrong one for its first track: measured over 33 real
 * mixes, 22 of them opened with an unjudged artist even though unjudged
 * candidates were only a quarter to a half of the pool. The softmax draw
 * over-represents them at the very front, and the opening track is the one the
 * listener judges the whole mix by - "the first song is always something random".
 *
 * So the opener is required to be an artist we can actually describe; everything
 * after it is left exactly as it was, so variety is unaffected.
 */
class MixOpenerTest {

    private val engine = MixEngine()

    private fun track(id: String, artist: String) = Track(
        itemId = id,
        provider = "deezer",
        name = "Track $id",
        uri = "deezer://track/$id",
        artistNames = artist,
        artistUri = "deezer://artist/$artist",
    )

    private fun candidate(id: String, artist: String, score: Double, verified: Boolean) =
        CandidateTrack(track = track(id, artist), score = score, verified = verified)

    @Test
    fun `a mix does not open with an artist we cannot describe`() {
        // The unjudged one outscores everything, which is exactly the situation
        // that produced the complaint.
        val candidates = listOf(
            candidate("1", "Unknown Act", score = 0.99, verified = false),
            candidate("2", "Known Act", score = 0.50, verified = true),
            candidate("3", "Another Known", score = 0.45, verified = true),
        )

        val mix = engine.buildFromCandidates(candidates, target = 3, randomSeed = 42L, discovery = 0.6)

        assertThat(mix).isNotEmpty()
        assertThat(mix.first().artistNames).isNotEqualTo("Unknown Act")
    }

    @Test
    fun `the unjudged candidate is still in the mix, just not first`() {
        // Keeping it matters: dropping unknowns is what made whole scenes vanish.
        val candidates = listOf(
            candidate("1", "Unknown Act", score = 0.99, verified = false),
            candidate("2", "Known Act", score = 0.50, verified = true),
            candidate("3", "Another Known", score = 0.45, verified = true),
        )

        val mix = engine.buildFromCandidates(candidates, target = 3, randomSeed = 42L, discovery = 0.6)

        assertThat(mix.map { it.artistNames }).contains("Unknown Act")
    }

    @Test
    fun `an unanchored mix still builds rather than coming back empty`() {
        // A brand-new library, or a scene MusicBrainz has never heard of: better a
        // mix of unknowns than no mix.
        //
        // `verified` USED to mean "we can describe this artist", and this case was
        // expressed by passing verified=false. It now means "fits the family this
        // mix is anchored on", and the no-genre library reaches the same outcome by
        // a different route: with no seed carrying a genre the cluster has no
        // families at all, `classifyFamily` answers ON for everything, and every
        // candidate arrives verified. So the case is modelled that way here.
        //
        // Passing verified=false would now assert the opposite of what the engine
        // must do: an ANCHORED mix whose candidates all disagree with it has to come
        // out short rather than full of unrelated tracks (see OffFamilyCapTest).
        val candidates = listOf(
            candidate("1", "Unknown A", score = 0.9, verified = true),
            candidate("2", "Unknown B", score = 0.8, verified = true),
        )

        val mix = engine.buildFromCandidates(candidates, target = 2, randomSeed = 7L, discovery = 0.5)

        assertThat(mix).hasSize(2)
    }

    @Test
    fun `ordering after the opener is left to the existing draw`() {
        // The guard promotes exactly one artist. If it leaked further it would
        // flatten the variety the Discovery knob is there to produce.
        val candidates = (1..8).map {
            candidate("$it", "Artist $it", score = 1.0 - it * 0.05, verified = it % 2 == 0)
        }

        val a = engine.buildFromCandidates(candidates, target = 6, randomSeed = 1L, discovery = 0.9)
        val b = engine.buildFromCandidates(candidates, target = 6, randomSeed = 2L, discovery = 0.9)

        assertThat(a.drop(1)).isNotEqualTo(b.drop(1))
    }
}
