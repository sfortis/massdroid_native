package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * An audiobook's authors and narrators reach us in two different shapes.
 *
 * Reported as issue #77 on 2026-09-28: with an Audiobookshelf provider configured, the
 * audiobook tab was empty and no audiobook was ever found by search, while the Music
 * Assistant web client and other apps showed the library in full. The server was answering
 * correctly every time. Music Assistant sends `authors` as bare strings while those people
 * are only names on a record, and as whole media items once they exist as artists in the
 * library, which a provider carrying author information makes happen on its first sync.
 * Declared as strings alone, the whole item then failed to decode and was dropped in
 * silence, so an unreadable answer and an empty library looked identical.
 */
class AudiobookAuthorShapeTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun audiobook(authors: String, narrators: String = "[]") = """
        {
          "item_id": "1",
          "provider": "library",
          "name": "Alice in Wonderland",
          "uri": "library://audiobook/1",
          "media_type": "audiobook",
          "is_playable": true,
          "duration": 10732,
          "authors": $authors,
          "narrators": $narrators
        }
    """.trimIndent()

    @Test
    fun `authors as plain strings`() {
        val item = json.decodeFromString<ServerMediaItem>(audiobook("""["Lewis Carroll"]"""))
        assertThat(item.authors).containsExactly("Lewis Carroll")
    }

    @Test
    fun `authors as media items keep only their names`() {
        // The shape Music Assistant sends once the author is an artist in the library.
        val asItems = """
            [{"item_id":"818","provider":"library","name":"Lewis Carroll","version":"",
              "sort_name":"Lewis Carroll","uri":"library://artist/818","external_ids":[],
              "is_playable":true,"media_type":"artist"}]
        """.trimIndent()
        val item = json.decodeFromString<ServerMediaItem>(audiobook(asItems))
        assertThat(item.authors).containsExactly("Lewis Carroll")
        assertThat(item.name).isEqualTo("Alice in Wonderland")
    }

    @Test
    fun `narrators take the same two shapes`() {
        val asItems = """[{"item_id":"9","provider":"library","name":"Some Narrator","uri":"library://artist/9"}]"""
        assertThat(json.decodeFromString<ServerMediaItem>(audiobook("[]", asItems)).narrators)
            .containsExactly("Some Narrator")
        assertThat(json.decodeFromString<ServerMediaItem>(audiobook("[]", """["Some Narrator"]""")).narrators)
            .containsExactly("Some Narrator")
    }

    @Test
    fun `an author object without a name does not fail the whole item`() {
        // One unreadable name must not cost the listener the audiobook itself.
        val item = json.decodeFromString<ServerMediaItem>(audiobook("""[{"item_id":"7"}]"""))
        assertThat(item.authors).containsExactly("")
        assertThat(item.uri).isEqualTo("library://audiobook/1")
    }

    @Test
    fun `the two shapes may be mixed in one list`() {
        val mixed = """["Lewis Carroll", {"item_id":"819","name":"Daniel Suarez"}]"""
        assertThat(json.decodeFromString<ServerMediaItem>(audiobook(mixed)).authors)
            .containsExactly("Lewis Carroll", "Daniel Suarez")
            .inOrder()
    }

    @Test
    fun `a track carries its number on the disc and the position in the album`() {
        // On a release of several discs these differ, and the sleeve shows the track number.
        val track = """
            {
              "item_id": "12",
              "provider": "library",
              "name": "Second disc, first song",
              "uri": "library://track/12",
              "media_type": "track",
              "position": 13,
              "track_number": 1,
              "disc_number": 2
            }
        """.trimIndent()
        val item = json.decodeFromString<ServerMediaItem>(track)
        assertThat(item.position).isEqualTo(13)
        assertThat(item.trackNumber).isEqualTo(1)
        assertThat(item.discNumber).isEqualTo(2)
    }

    @Test
    fun `a playlist carries what it accepts`() {
        val playlist = """
            {
              "item_id": "3",
              "provider": "library",
              "name": "Books only",
              "uri": "library://playlist/3",
              "media_type": "playlist",
              "is_editable": true,
              "supported_mediatypes": ["audiobook", "podcast_episode"]
            }
        """.trimIndent()
        val item = json.decodeFromString<ServerMediaItem>(playlist)
        assertThat(item.supportedMediaTypes).containsExactly("audiobook", "podcast_episode")
    }

    @Test
    fun `an older server that omits these leaves them null`() {
        val bare = """{"item_id":"1","provider":"library","name":"x","uri":"library://track/1"}"""
        val item = json.decodeFromString<ServerMediaItem>(bare)
        assertThat(item.trackNumber).isNull()
        assertThat(item.discNumber).isNull()
        assertThat(item.supportedMediaTypes).isNull()
    }
}
