package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * Pins that off-family material can only ever garnish a mix.
 *
 * This replaces a score penalty that failed in the field. Scaling an off-family
 * candidate's score only orders it against other candidates, so when on-family
 * material is scarce the off-family tail fills everything left over. A real build
 * anchored on Sinkane (experimental/funk/jazz) had 9 on-family candidates against
 * 67 off-family ones, and roughly three quarters of the resulting 33-track mix was
 * material the cluster never asked for. The listener called the mixes unacceptable.
 *
 * A cluster with little on-family material must now produce a SHORTER mix rather
 * than a full one that drifts out of its genre.
 */
class OffFamilyCapTest {

    private val engine = MixEngine()

    private fun candidate(id: Int, score: Double, onFamily: Boolean) = CandidateTrack(
        track = Track(
            itemId = "$id",
            provider = "deezer",
            name = "Track $id",
            uri = "deezer://track/$id",
            artistNames = "Artist $id",
            artistUri = "library://artist/$id"
        ),
        score = score,
        verified = onFamily
    )

    @Test
    fun `a scarce on-family pool yields a short mix, not an off-genre full one`() {
        // The measured Sinkane shape: 9 on-family, 67 off-family, target 33.
        val onFamily = (1..9).map { candidate(it, score = 0.4, onFamily = true) }
        // Off-family with HIGHER scores, which is exactly what the old multiplier
        // allowed: a close off-family candidate outranked a distant on-family one.
        val offFamily = (100..166).map { candidate(it, score = 0.9, onFamily = false) }

        val mix = engine.buildFromCandidates(onFamily + offFamily, target = 33, randomSeed = 1L)

        val offInMix = mix.count { it.itemId.toInt() >= 100 }
        // At most 20% of the target may be off-family.
        assertThat(offInMix).isAtMost(7)
        // And the mix is allowed to come out short rather than pad itself.
        assertThat(mix.size).isLessThan(33)
    }

    @Test
    fun `a healthy on-family pool is not touched by the cap`() {
        val onFamily = (1..40).map { candidate(it, score = 0.8, onFamily = true) }
        val offFamily = (100..104).map { candidate(it, score = 0.3, onFamily = false) }

        val mix = engine.buildFromCandidates(onFamily + offFamily, target = 33, randomSeed = 1L)

        assertThat(mix).hasSize(33)
        // Nothing forces off-family in when on-family material is plentiful, but
        // the few that exist are not banned either; the cap is a ceiling only.
        assertThat(mix.count { it.itemId.toInt() >= 100 }).isAtMost(7)
    }

    @Test
    fun `an all-off-family pool is still capped, so such a cluster yields little`() {
        // No on-family candidate at all: the honest outcome is a mix so short the
        // caller's min-tracks floor rejects it, rather than 33 unrelated tracks.
        val offFamily = (100..160).map { candidate(it, score = 0.9, onFamily = false) }

        val mix = engine.buildFromCandidates(offFamily, target = 33, randomSeed = 1L)

        assertThat(mix.size).isAtMost(7)
    }

    @Test
    fun `an unanchored mix is unaffected, since everything reads as on-family`() {
        // classifyFamily returns ON when the mix has no families to disagree with,
        // so every candidate arrives verified and the cap never engages.
        val all = (1..40).map { candidate(it, score = 0.7, onFamily = true) }

        assertThat(engine.buildFromCandidates(all, target = 33, randomSeed = 1L)).hasSize(33)
    }
}
