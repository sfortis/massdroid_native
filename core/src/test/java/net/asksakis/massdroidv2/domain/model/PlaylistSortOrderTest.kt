package net.asksakis.massdroidv2.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the listing order to Music Assistant's own `sort_tracks`
 * (`music_assistant/controllers/player_queues/helpers.py`).
 *
 * This matters because the order decides how a playlist is played. Once the app hands the
 * server a sort key instead of an expanded track list, any difference between the two sorts
 * shows up as a queue in a different order than the screen, with nothing to signal it. Each
 * case below fails under the sort the app used before: plain name, all artists joined, and a
 * reversed list for descending.
 */
class PlaylistSortOrderTest {

    private fun track(
        name: String,
        sortName: String = name,
        artists: String = "",
        artistSort: String = artists,
        album: String = "",
        albumSort: String = album,
        duration: Double? = null,
        position: Int? = null,
        dateAdded: String? = null
    ) = Track(
        itemId = name,
        provider = "library",
        name = name,
        uri = "library://track/$name",
        duration = duration,
        artistNames = artists,
        albumName = album,
        position = position,
        dateAdded = dateAdded,
        sortName = sortName,
        primaryArtistSortName = artistSort,
        albumSortName = albumSort
    )

    private fun List<Track>.names() = map { it.name }

    @Test
    fun `name sorts by the server's sort name, not the displayed name`() {
        // MA strips the leading article when it builds a sort name, so "The Mole" belongs
        // under M. Sorting the displayed name instead puts it after "Nadir".
        val tracks = listOf(
            track("Nadir", sortName = "nadir"),
            track("The Mole", sortName = "mole, the")
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.NAME)

        assertThat(sorted.names()).containsExactly("The Mole", "Nadir").inOrder()
    }

    @Test
    fun `name sorts by the stripped sort name, so a diacritic does not sort after z`() {
        // MA strips diacritics into the sort name. Sorting the displayed name compares the
        // raw code point, where "é" sits above every plain letter and "Étude" lands last.
        val tracks = listOf(
            track("Fugue", sortName = "fugue"),
            track("Étude", sortName = "etude")
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.NAME)

        assertThat(sorted.names()).containsExactly("Étude", "Fugue").inOrder()
    }

    @Test
    fun `artist sorts by the first artist alone, not by all of them joined`() {
        // The server reads artists[0] and its sort name. Joining every artist compares
        // "the beatles, zed", which sorts after "cure" instead of before it.
        val tracks = listOf(
            track("collab", artists = "The Beatles, Zed", artistSort = "beatles, the"),
            track("solo", artists = "Cure", artistSort = "cure")
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.ARTIST)

        assertThat(sorted.names()).containsExactly("collab", "solo").inOrder()
    }

    @Test
    fun `descending duration sorts rather than reverses, so equal lengths keep their order`() {
        // `duration_desc` is a sort with reverse=True, which is stable. Reversing the
        // ascending list instead swaps every pair of equal-length tracks.
        val tracks = listOf(
            track("first equal", duration = 100.0),
            track("second equal", duration = 100.0),
            track("longest", duration = 300.0)
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.DURATION_DESC)

        assertThat(sorted.names()).containsExactly("longest", "first equal", "second equal").inOrder()
    }

    @Test
    fun `descending position sorts on the position, so a track without one keeps its place`() {
        val tracks = listOf(
            track("first", position = 1),
            track("unplaced", position = null),
            track("third", position = 3)
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.POSITION_DESC)

        assertThat(sorted.names()).containsExactly("third", "first", "unplaced").inOrder()
    }

    @Test
    fun `ascending position leaves the server's own order untouched`() {
        val tracks = listOf(track("c", position = 3), track("a", position = 1), track("b", position = 2))

        val sorted = tracks.sortedForListing(PlaylistSortKey.POSITION)

        assertThat(sorted.names()).containsExactly("c", "a", "b").inOrder()
    }



    @Test
    fun `album sorts by the album's sort name, not by its displayed name`() {
        // "album, the" sorts before "blue"; the displayed "The Album" sorts after it.
        val tracks = listOf(
            track("b", album = "Blue", albumSort = "blue"),
            track("a", album = "The Album", albumSort = "album, the")
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.ALBUM)

        assertThat(sorted.names()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a stored reverse becomes its own key`() {
        assertThat(playlistSortKeyOf("POSITION", storedDescending = true))
            .isEqualTo(PlaylistSortKey.POSITION_DESC)
        assertThat(playlistSortKeyOf("DURATION", storedDescending = true))
            .isEqualTo(PlaylistSortKey.DURATION_DESC)
    }

    @Test
    fun `a stored reverse the server cannot produce falls back to the plain order`() {
        // Name, artist and album had a reverse before the orders were aligned. The server has
        // no key for it, so the reverse is dropped rather than silently kept.
        assertThat(playlistSortKeyOf("NAME", storedDescending = true)).isEqualTo(PlaylistSortKey.NAME)
        assertThat(playlistSortKeyOf("ARTIST", storedDescending = true)).isEqualTo(PlaylistSortKey.ARTIST)
        assertThat(playlistSortKeyOf("ALBUM", storedDescending = true)).isEqualTo(PlaylistSortKey.ALBUM)
    }

    @Test
    fun `an order that no longer exists falls back to the playlist's own`() {
        // RECENTLY_ADDED was the app's own and has no server key.
        assertThat(playlistSortKeyOf("RECENTLY_ADDED", storedDescending = false))
            .isEqualTo(PlaylistSortKey.POSITION)
        assertThat(playlistSortKeyOf("", storedDescending = false)).isEqualTo(PlaylistSortKey.POSITION)
    }
}
