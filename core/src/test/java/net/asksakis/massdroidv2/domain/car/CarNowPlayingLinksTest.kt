package net.asksakis.massdroidv2.domain.car

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.car.CarNowPlayingLinks.Kind
import net.asksakis.massdroidv2.domain.car.CarNowPlayingLinks.Target
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/** Issue #86: the car's playing screen links its album and artist lines to their pages. */
class CarNowPlayingLinksTest {

    private fun track(
        mediaType: MediaType = MediaType.TRACK,
        artistNames: String = "Apparat",
        artistProvider: String? = "deezer",
        artistItemId: String? = "1201",
        artistUri: String? = null,
        albumName: String = "A Hum Of Maybe",
        albumProvider: String? = "library",
        albumItemId: String? = "77"
    ) = Track(
        itemId = "5",
        provider = "library",
        name = "Song",
        uri = "library://track/5",
        artistNames = artistNames,
        albumName = albumName,
        artistItemId = artistItemId,
        artistProvider = artistProvider,
        albumItemId = albumItemId,
        albumProvider = albumProvider,
        artistUri = artistUri,
        mediaType = mediaType
    )

    @Test
    fun `a track links its album and its first artist`() {
        val links = CarNowPlayingLinks.forTrack(track())
        assertThat(links.albumMediaId).isEqualTo("album|library|77")
        assertThat(links.artistMediaId).isEqualTo("artist|deezer|1201")
    }

    /** The library entry's page lists the artist's albums wherever the track came from. */
    @Test
    fun `the artist link prefers the library entry the app mapped`() {
        val links = CarNowPlayingLinks.forTrack(track(artistUri = "library://artist/59"))
        assertThat(links.artistMediaId).isEqualTo("artist|library|59")
    }

    @Test
    fun `a provider artist uri is not taken for a library entry`() {
        val links = CarNowPlayingLinks.forTrack(track(artistUri = "deezer://artist/1201"))
        assertThat(links.artistMediaId).isEqualTo("artist|deezer|1201")
    }

    @Test
    fun `radio, podcast episodes and audiobooks have no links`() {
        for (type in listOf(MediaType.RADIO, MediaType.PODCAST_EPISODE, MediaType.AUDIOBOOK)) {
            assertThat(CarNowPlayingLinks.forTrack(track(mediaType = type))).isEqualTo(CarNowPlayingLinks.Links.None)
        }
        assertThat(CarNowPlayingLinks.forTrack(null)).isEqualTo(CarNowPlayingLinks.Links.None)
    }

    @Test
    fun `a missing id leaves only that link out`() {
        val noAlbumId = CarNowPlayingLinks.forTrack(track(albumItemId = null))
        assertThat(noAlbumId.albumMediaId).isNull()
        assertThat(noAlbumId.artistMediaId).isEqualTo("artist|deezer|1201")
        val noArtistProvider = CarNowPlayingLinks.forTrack(track(artistProvider = ""))
        assertThat(noArtistProvider.artistMediaId).isNull()
        assertThat(noArtistProvider.albumMediaId).isEqualTo("album|library|77")
    }

    /** The host links a line only when it has text to show. */
    @Test
    fun `a line with no name gets no link`() {
        val links = CarNowPlayingLinks.forTrack(track(artistNames = " ", albumName = ""))
        assertThat(links).isEqualTo(CarNowPlayingLinks.Links.None)
    }

    @Test
    fun `parse reads back the artist and album ids`() {
        assertThat(CarNowPlayingLinks.parse("artist|deezer|1201")).isEqualTo(Target(Kind.ARTIST, "deezer", "1201"))
        assertThat(CarNowPlayingLinks.parse(CarNowPlayingLinks.albumMediaId("library", "77")))
            .isEqualTo(Target(Kind.ALBUM, "library", "77"))
    }

    @Test
    fun `parse rejects other ids`() {
        assertThat(CarNowPlayingLinks.parse("playlist|library|3")).isNull()
        assertThat(CarNowPlayingLinks.parse("track|library|5")).isNull()
        assertThat(CarNowPlayingLinks.parse("artist|deezer")).isNull()
        assertThat(CarNowPlayingLinks.parse("album||77")).isNull()
        assertThat(CarNowPlayingLinks.parse("root")).isNull()
    }
}
