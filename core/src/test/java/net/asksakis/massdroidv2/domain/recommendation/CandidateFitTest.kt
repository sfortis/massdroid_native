package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * Pins how a candidate is used by its family match. A candidate without a
 * resolvable genre used to be dropped, which left mostly well-known artists in a
 * mix; it now takes the family of the seed that found it.
 */
class CandidateFitTest {

    @Test
    fun `an undescribable candidate is kept at full score but cannot open the mix`() {
        val fit = candidateFit(FamilyMatch.UNKNOWN)
        assertThat(fit.scale).isEqualTo(1.0)
        assertThat(fit.verified).isFalse()
    }

    @Test
    fun `an artist with no family entry at all is treated as undescribable`() {
        assertThat(candidateFit(null)).isEqualTo(candidateFit(FamilyMatch.UNKNOWN))
    }

    @Test
    fun `an on-family candidate opens the mix and an off-family one is scaled down`() {
        assertThat(candidateFit(FamilyMatch.ON)).isEqualTo(CandidateFit(scale = 1.0, verified = true, priority = 0))
        val off = candidateFit(FamilyMatch.OFF)
        assertThat(off.verified).isFalse()
        assertThat(off.scale).isLessThan(1.0)
    }

    @Test
    fun `inherited candidates are taken after on-family ones and before off-family ones`() {
        val order = listOf(FamilyMatch.OFF, FamilyMatch.UNKNOWN, FamilyMatch.ON)
            .sortedBy { candidateFit(it).priority }
        assertThat(order).containsExactly(FamilyMatch.ON, FamilyMatch.UNKNOWN, FamilyMatch.OFF).inOrder()
    }

    @Test
    fun `an inherited copy that wins on score keeps the verified flag of the on-family copy`() {
        fun candidate(score: Double, verified: Boolean) = CandidateTrack(
            track = Track(
                itemId = "1",
                provider = "deezer",
                name = "Track 1",
                uri = "deezer://track/1",
                artistNames = "Artist"
            ),
            score = score,
            verified = verified
        )
        val fromArtists = listOf(candidate(0.6, verified = true))
        val fromTracks = listOf(candidate(0.9, verified = false))

        val merged = mergeRoutes(fromArtists, fromTracks).single()

        assertThat(merged.verified).isTrue()
        // The better score is kept, with the agreement bonus on top.
        assertThat(merged.score).isWithin(1e-9).of(0.9 + 0.12)
    }
}
