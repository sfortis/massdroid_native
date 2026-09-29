package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.domain.model.LibraryTabKey
import org.junit.Test

/**
 * Reading which kind of item a media event is about, and matching it to the list it changed.
 *
 * An add or a delete only tells the library which tab went out of date, and a tab that is not
 * told keeps showing an item the server no longer has: a book deleted while the listener was
 * on another tab stayed in the audiobook list until a pull to refresh. An event whose kind
 * cannot be read has to outdate everything, so the fallback matters as much as the match.
 */
class EventMediaTypeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun event(payload: String) =
        json.decodeFromString<ServerEvent>(
            """{"event":"media_item_deleted","object_id":"library://audiobook/2","data":$payload}"""
        )

    @Test
    fun `the kind is read from the top of the payload`() {
        assertThat(event("""{"media_type":"audiobook"}""").mediaType()).isEqualTo("audiobook")
    }

    @Test
    fun `the kind is read from a nested media item`() {
        assertThat(event("""{"media_item":{"media_type":"playlist"}}""").mediaType())
            .isEqualTo("playlist")
    }

    @Test
    fun `the top level wins over the nested one`() {
        val payload = """{"media_type":"track","media_item":{"media_type":"album"}}"""
        assertThat(event(payload).mediaType()).isEqualTo("track")
    }

    @Test
    fun `it is lowercased`() {
        assertThat(event("""{"media_type":"AUDIOBOOK"}""").mediaType()).isEqualTo("audiobook")
    }

    @Test
    fun `an event that does not say returns null`() {
        assertThat(event("""{}""").mediaType()).isNull()
        assertThat(event("""{"media_type":""}""").mediaType()).isNull()
        assertThat(event("null").mediaType()).isNull()
    }

    @Test
    fun `every kind the server names has a tab that claims it`() {
        // The mapping is what decides which list is refetched, so a kind with no tab would
        // silently leave that list stale.
        for (kind in listOf("artist", "album", "track", "playlist", "radio", "audiobook", "podcast")) {
            val tab = LibraryTabKey.entries.firstOrNull { it.mediaTypeName == kind }
            assertThat(tab).isNotNull()
        }
    }

    @Test
    fun `browse claims no kind of its own`() {
        assertThat(LibraryTabKey.BROWSE.mediaTypeName).isNull()
    }

    @Test
    fun `no two tabs claim the same kind`() {
        val named = LibraryTabKey.entries.mapNotNull { it.mediaTypeName }
        assertThat(named).containsNoDuplicates()
    }
}
