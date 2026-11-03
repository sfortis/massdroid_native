package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import net.asksakis.massdroidv2.domain.model.AlbumType
import org.junit.Test

/**
 * The library filters reach Music Assistant under the names its `library_items` commands read
 * (`album_artists_only` for artists, `album_types` for albums), and an unset filter sends
 * nothing, so an older server sees the request it saw before.
 */
class LibraryItemsFilterArgsTest {

    @Test
    fun `the album artist filter is sent only when it is on`() {
        val on = LibraryItemsArgs(limit = 50, offset = 0, albumArtistsOnly = true).toJson()
        val off = LibraryItemsArgs(limit = 50, offset = 0).toJson()
        assertThat(on["album_artists_only"]).isEqualTo(JsonPrimitive(true))
        assertThat(off.containsKey("album_artists_only")).isFalse()
    }

    @Test
    fun `album types are sent as the server spells them`() {
        val args = LibraryItemsArgs(
            limit = 50,
            offset = 0,
            albumTypes = listOf(AlbumType.ALBUM, AlbumType.EP).map { it.serverValue }
        ).toJson()
        assertThat(args["album_types"]).isEqualTo(JsonArray(listOf(JsonPrimitive("album"), JsonPrimitive("ep"))))
        assertThat(LibraryItemsArgs(limit = 50, offset = 0).toJson().containsKey("album_types")).isFalse()
    }
}
