package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * Pins how the mix generators test a blocked artist.
 *
 * Why it matters: on 2026-10-16 a Smart Mix played two tracks by the blocked "Étienne Daho"
 * and one by the blocked "Christophe". The provider sends the first as "Etienne Daho", and the
 * old name key dropped the accented letter ("tienne daho"); the second was credited together
 * with Sébastien Tellier, and the joined name never equalled the blocked one. Both carried the
 * very artist URIs the blocks were stored under.
 */
class BlockedArtistsTest {

    private fun track(names: String, vararg artistUris: String) = Track(
        itemId = "1",
        provider = "deezer--x",
        name = "Some Track",
        uri = "deezer--x://track/1",
        artistNames = names,
        artistUri = artistUris.firstOrNull(),
        artistUris = artistUris.toList()
    )

    @Test
    fun `a blocked uri blocks the artist under any spelling`() {
        val blocked = BlockedArtists(uris = setOf("deezer--x://artist/3251"), names = emptySet())

        assertThat(blocked.blocksAnyArtistOf(track("Etienne Daho", "deezer--x://artist/3251"))).isTrue()
        assertThat(blocked.blocksArtist("deezer--x://artist/3251", "Etienne Daho")).isTrue()
    }

    @Test
    fun `an accented blocked name matches the unaccented spelling`() {
        val blocked = BlockedArtists(uris = emptySet(), names = setOf("Étienne Daho"))

        assertThat(blocked.blocksArtist("library://artist/9", "Etienne Daho")).isTrue()
        assertThat(blocked.blocksAnyArtistOf(track("Etienne Daho", "library://artist/9"))).isTrue()
    }

    @Test
    fun `any credited artist of a track is tested`() {
        val blocked = BlockedArtists(uris = emptySet(), names = setOf("Christophe"))

        val duet = track("Christophe, Sébastien Tellier", "library://artist/1", "library://artist/2")
        assertThat(blocked.blocksAnyArtistOf(duet)).isTrue()
    }

    @Test
    fun `a secondary artist blocked by uri blocks the track`() {
        val blocked = BlockedArtists(uris = setOf("deezer--x://artist/1627"), names = emptySet())

        val duet = track("Sébastien Tellier, Christophe", "deezer--x://artist/1408", "deezer--x://artist/1627")
        assertThat(blocked.blocksAnyArtistOf(duet)).isTrue()
    }

    @Test
    fun `an artist whose own name holds a comma still matches`() {
        val blocked = BlockedArtists(uris = emptySet(), names = setOf("Earth, Wind & Fire"))

        assertThat(blocked.blocksAnyArtistOf(track("Earth, Wind & Fire"))).isTrue()
    }

    @Test
    fun `a non latin blocked name blocks that artist and no other`() {
        val blocked = BlockedArtists(uris = emptySet(), names = setOf("Μάνος Χατζιδάκις"))

        assertThat(blocked.blocksArtist(null, "Μάνος Χατζιδάκις")).isTrue()
        assertThat(blocked.blocksArtist(null, "Σωκράτης Μάλαμας")).isFalse()
    }

    @Test
    fun `an unrelated artist is not blocked`() {
        val blocked = BlockedArtists(uris = setOf("deezer--x://artist/3251"), names = setOf("Étienne Daho"))

        assertThat(blocked.blocksAnyArtistOf(track("Perfume Genius", "deezer--x://artist/467549"))).isFalse()
        assertThat(BlockedArtists.NONE.blocksAnyArtistOf(track("Etienne Daho"))).isFalse()
    }

    @Test
    fun `the artist key drops accents and brackets but keeps every alphabet`() {
        assertThat(normalizeArtistKey("Röyksopp (Live)")).isEqualTo("royksopp")
        assertThat(normalizeArtistKey("Étienne Daho")).isEqualTo(normalizeArtistKey("Etienne Daho"))
        assertThat(normalizeArtistKey("Μάνος Χατζιδάκις")).isEqualTo("μανος χατζιδακις")
    }
}
