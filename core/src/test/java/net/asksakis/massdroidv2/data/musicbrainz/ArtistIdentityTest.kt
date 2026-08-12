package net.asksakis.massdroidv2.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how an artist is identified from a MusicBrainz name search.
 *
 * Why this is not a detail. The genres of the PRIMARY seed become the genre
 * envelope of an entire Smart Mix, so misidentifying one artist mis-genres all
 * 33 tracks. That is not hypothetical: searching "Labelle" returns the American
 * soul group "LaBelle" at score 100 and the exact "Labelle" - the Reunion Island
 * electronic producer actually being played - at 86. The old code took the top
 * hit, so a maloya-electronic artist was described as disco/funk/pop soul/soul,
 * and the mix built on that envelope filled up with French chanson.
 *
 * A score threshold could never have caught it: only ONE result cleared the bar,
 * and it was the wrong one.
 */
class ArtistIdentityTest {

    private fun c(name: String, score: Int, id: String = name.lowercase()) =
        MbArtistCandidate(id = id, name = name, score = score)

    @Test
    fun `an exact name match beats a higher-scoring namesake`() {
        val results = listOf(
            c("LaBelle", 100, "wrong-us-soul-group"),
            c("Patti LaBelle", 97, "wrong-person"),
            c("Labelle", 86, "right-reunion-producer"),
        )
        assertThat(pickArtistCandidate(results, "Labelle")).isEqualTo("right-reunion-producer")
    }

    @Test
    fun `exactness is case-sensitive, which is the whole point here`() {
        // "LaBelle" and "Labelle" are the same string case-insensitively, so a
        // case-folding comparison would call both exact and settle it on score -
        // reinstating the bug.
        val results = listOf(c("LaBelle", 100, "wrong"), c("Labelle", 86, "right"))
        assertThat(pickArtistCandidate(results, "Labelle")).isEqualTo("right")
        assertThat(pickArtistCandidate(results, "LaBelle")).isEqualTo("wrong")
    }

    @Test
    fun `with no exact match the fuzzy score still decides`() {
        val results = listOf(c("Bjork", 95, "close"), c("Bjorn", 80, "far"))
        assertThat(pickArtistCandidate(results, "Björk")).isEqualTo("close")
    }

    @Test
    fun `a weak best match is rejected rather than guessed at`() {
        // Better no genres than a stranger's: an unjudged candidate is merely
        // uninformative, a misjudged one poisons the gate.
        val results = listOf(c("Something Else", 55), c("Another Thing", 40))
        assertThat(pickArtistCandidate(results, "Obscure Band")).isNull()
    }

    @Test
    fun `an exact match is trusted below the fuzzy bar, but not at any score`() {
        // The name already agreed character for character, so the score is
        // corroboration - but a 20 means MusicBrainz thinks it is a different
        // entity entirely.
        assertThat(pickArtistCandidate(listOf(c("Air", 85, "air")), "Air")).isEqualTo("air")
        assertThat(pickArtistCandidate(listOf(c("Air", 20, "air")), "Air")).isNull()
    }

    @Test
    fun `no candidates means no identity`() {
        assertThat(pickArtistCandidate(emptyList(), "Nobody")).isNull()
    }
}
