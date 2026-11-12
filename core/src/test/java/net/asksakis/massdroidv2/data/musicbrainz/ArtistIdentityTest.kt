package net.asksakis.massdroidv2.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how an artist is identified from a MusicBrainz name search.
 *
 * Why this is not a detail. The genres of the PRIMARY seed become the genre
 * envelope of an entire Smart Mix, and a misidentified artist the user plays often
 * shifts every genre score. The spelling does not tell namesakes apart: preferring
 * the exact spelling turned the ambient "Aes Dana" and the psy-chill "ASURA" into a
 * black metal and a power metal band. Among candidates carrying the name, the best
 * one wins only by a clear score margin ([NAME_TIE_MARGIN]); closer than that, the
 * name identifies nobody. The search results below were measured on 2026-11-12.
 */
class ArtistIdentityTest {

    private fun c(name: String, score: Int, id: String = name.lowercase()) =
        MbArtistCandidate(id = id, name = name, score = score)

    /** Margin 13: the ambient project being played, not the black metal band spelled like it. */
    @Test
    fun `Aes Dana resolves to the ambient project by its score lead`() {
        val results = listOf(
            c("AES Dana", 100, "ed5cd4a2-ambient-project"),
            c("Aes Dana", 87, "4b1591fd-black-metal-band"),
        )
        assertThat(pickArtistCandidate(results, "Aes Dana")).isEqualTo("ed5cd4a2-ambient-project")
    }

    /** Margin 11 over the next "Asura"; the power metal "ASURA" sits further down. */
    @Test
    fun `ASURA resolves to the psy-chill artist by its score lead`() {
        val results = listOf(
            c("Asura", 100, "49a5f5b9-psy-chill"),
            c("Asura", 89, "15612ed2-la-electronic"),
            c("ASURA", 88, "6ca61520-jp-power-metal"),
            c("Asura", 86, "5beae1e8-us-grindcore"),
        )
        assertThat(pickArtistCandidate(results, "ASURA")).isEqualTo("49a5f5b9-psy-chill")
    }

    /**
     * Margin 14 picks the US soul group, while the owner plays the Reunion Island
     * producer. Accepted by the owner for callers with no recording to go by; the
     * history path and the repair are decided by the recording first.
     */
    @Test
    fun `Labelle by name alone resolves to the higher-scoring LaBelle`() {
        val results = listOf(
            c("LaBelle", 100, "us-soul-group"),
            c("Patti LaBelle", 97, "person"),
            c("Labelle", 86, "reunion-producer"),
        )
        assertThat(pickArtistCandidate(results, "Labelle")).isEqualTo("us-soul-group")
    }

    @Test
    fun `namesakes closer than the margin identify nobody`() {
        val results = listOf(c("Genesis", 100, "uk-band"), c("Genesis", 95, "other-band"))
        assertThat(isAmbiguousName(results, "Genesis")).isTrue()
        assertThat(pickArtistCandidate(results, "Genesis")).isNull()
    }

    @Test
    fun `a lead of exactly the margin is enough`() {
        val results = listOf(c("Air", 100, "french-duo"), c("Air", 100 - NAME_TIE_MARGIN, "other"))
        assertThat(pickArtistCandidate(results, "Air")).isEqualTo("french-duo")
    }

    @Test
    fun `a name only one candidate carries identifies it, whatever its case`() {
        val results = listOf(c("Bonobo", 100, "bonobo"), c("Bonobo Rodeo", 70, "other"))
        assertThat(pickArtistCandidate(results, "BONOBO")).isEqualTo("bonobo")
    }

    @Test
    fun `a namesake below the name bar does not make the name ambiguous`() {
        val results = listOf(c("Air", 100, "air-french-duo"), c("AIR", 60, "other"))
        assertThat(pickArtistCandidate(results, "Air")).isEqualTo("air-french-duo")
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
