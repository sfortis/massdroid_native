package net.asksakis.massdroidv2.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which playlists may be offered when adding a track.
 *
 * The rule used to be "editable and not dynamic", which was built to keep smart playlists out
 * of the dialog. It cannot see the other case: Music Assistant tells each playlist what it
 * accepts through `supported_mediatypes`, and a provider may offer playlists that hold only
 * audiobooks or only podcast episodes. Audiobookshelf declares exactly those two capabilities.
 * Such a playlist is editable and not dynamic, so it passed every test we had, appeared in the
 * add-to-playlist dialog for an ordinary song, and the server refused it on tap.
 */
class PlaylistAcceptsTracksTest {

    private fun playlist(
        editable: Boolean = true,
        dynamic: Boolean = false,
        supported: List<String> = listOf("track")
    ) = Playlist(
        itemId = "1",
        provider = "library",
        name = "A playlist",
        uri = "library://playlist/1",
        isEditable = editable,
        isDynamic = dynamic,
        supportedMediaTypes = supported
    )

    @Test
    fun `a playlist that takes tracks is offered`() {
        assertThat(playlist().acceptsManualTracks).isTrue()
    }

    @Test
    fun `a playlist that takes only audiobooks is not offered`() {
        assertThat(playlist(supported = listOf("audiobook")).acceptsManualTracks).isFalse()
    }

    @Test
    fun `a playlist that takes only podcast episodes is not offered`() {
        assertThat(playlist(supported = listOf("podcast_episode")).acceptsManualTracks).isFalse()
    }

    @Test
    fun `a mixed playlist that includes tracks is offered`() {
        val mixed = listOf("radio", "audiobook", "podcast_episode", "track")
        assertThat(playlist(supported = mixed).acceptsManualTracks).isTrue()
    }

    @Test
    fun `a server that does not say falls back to the old rule`() {
        // Older servers leave the field out entirely; the two flags are all there is.
        assertThat(playlist(supported = emptyList()).acceptsManualTracks).isTrue()
        assertThat(playlist(supported = emptyList(), dynamic = true).acceptsManualTracks).isFalse()
        assertThat(playlist(supported = emptyList(), editable = false).acceptsManualTracks).isFalse()
    }

    @Test
    fun `taking tracks does not rescue a read only or dynamic playlist`() {
        assertThat(playlist(editable = false).acceptsManualTracks).isFalse()
        assertThat(playlist(dynamic = true).acceptsManualTracks).isFalse()
    }
}
