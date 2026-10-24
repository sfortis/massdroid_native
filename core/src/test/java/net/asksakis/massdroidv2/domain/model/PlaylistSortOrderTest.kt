package net.asksakis.massdroidv2.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the listing order to the order Music Assistant plays a sorted playlist in, for each
 * server line the app supports.
 *
 * This matters because the order decides how a playlist is played. Once the app hands the
 * server a sort key instead of an expanded track list, any difference between the two sorts
 * shows up as a queue in a different order than the screen, with nothing to signal it. The
 * server changed its rules in 2.10.7 (PR #6906) and again in 2.11 (PR #7004), so each case
 * names the rules it holds for.
 */
class PlaylistSortOrderTest {

    private fun track(
        name: String,
        sortName: String = name,
        artists: String = "",
        artistSort: String = artists,
        firstArtist: String = artists,
        album: String = "",
        albumSort: String = album,
        duration: Double? = null,
        position: Int? = null,
        dateAdded: String? = null,
        disc: Int? = null,
        number: Int? = null
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
        primaryArtistName = firstArtist,
        albumSortName = albumSort,
        discNumber = disc,
        trackNumber = number
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

        val sorted = tracks.sortedForListing(PlaylistSortKey.NAME, PlaylistSortRules.SORT_NAMES)

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

        val sorted = tracks.sortedForListing(PlaylistSortKey.NAME, PlaylistSortRules.SORT_NAMES)

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

        val sorted = tracks.sortedForListing(PlaylistSortKey.ARTIST, PlaylistSortRules.SORT_NAMES)

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

        val sorted = tracks.sortedForListing(PlaylistSortKey.DURATION_DESC, PlaylistSortRules.SORT_NAMES)

        assertThat(sorted.names()).containsExactly("longest", "first equal", "second equal").inOrder()
    }

    @Test
    fun `descending position sorts on the position, so a track without one keeps its place`() {
        val tracks = listOf(
            track("first", position = 1),
            track("unplaced", position = null),
            track("third", position = 3)
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.POSITION_DESC, PlaylistSortRules.SORT_NAMES)

        assertThat(sorted.names()).containsExactly("third", "first", "unplaced").inOrder()
    }

    @Test
    fun `ascending position leaves the server's own order untouched`() {
        val tracks = listOf(track("c", position = 3), track("a", position = 1), track("b", position = 2))

        val sorted = tracks.sortedForListing(PlaylistSortKey.POSITION, PlaylistSortRules.SORT_NAMES)

        assertThat(sorted.names()).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `album sorts by the album's sort name, not by its displayed name`() {
        // "album, the" sorts before "blue"; the displayed "The Album" sorts after it.
        val tracks = listOf(
            track("b", album = "Blue", albumSort = "blue"),
            track("a", album = "The Album", albumSort = "album, the")
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.ALBUM, PlaylistSortRules.SORT_NAMES)

        assertThat(sorted.names()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `from 2_10_7 name sorts by the displayed name, so the article and the accent count`() {
        // PR #6906: `sort_tracks` sorts on the displayed name in lower case. "The Mole" now
        // belongs under T, and "Étude" sits above every plain letter by code point.
        val tracks = listOf(
            track("Étude", sortName = "etude"),
            track("The Mole", sortName = "mole, the"),
            track("Nadir", sortName = "nadir")
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.NAME, PlaylistSortRules.DISPLAY_NAMES)

        assertThat(sorted.names()).containsExactly("Nadir", "The Mole", "Étude").inOrder()
    }

    @Test
    fun `from 2_10_7 artist sorts by the displayed name of the first artist`() {
        // The server reads artists[0].name. The sort name would put "The Beatles" under B,
        // and all artists joined would compare "zed, the beatles".
        val tracks = listOf(
            track("collab", artists = "Zed, The Beatles", artistSort = "zed", firstArtist = "Zed"),
            track("band", artists = "The Beatles", artistSort = "beatles, the", firstArtist = "The Beatles"),
            track("solo", artists = "Cure", artistSort = "cure", firstArtist = "Cure")
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.ARTIST, PlaylistSortRules.DISPLAY_NAMES)

        assertThat(sorted.names()).containsExactly("solo", "band", "collab").inOrder()
    }

    @Test
    fun `from 2_10_7 album sorts by the displayed album name, then disc and track number`() {
        // The server's key is (album name, disc, track). The playlist lists the album's tracks
        // out of order on purpose: the playlist order must not survive within an album.
        val tracks = listOf(
            track("b2", album = "Blue", disc = 2, number = 1),
            track("the", album = "The Album", albumSort = "album, the", disc = 1, number = 1),
            track("b1-3", album = "Blue", disc = 1, number = 3),
            track("b1-1", album = "Blue", disc = 1, number = 1)
        )

        val sorted = tracks.sortedForListing(PlaylistSortKey.ALBUM, PlaylistSortRules.DISPLAY_NAMES)

        assertThat(sorted.names()).containsExactly("b1-1", "b1-3", "b2", "the").inOrder()
    }

    @Test
    fun `position and duration sort the same under every server's rules`() {
        val tracks = listOf(track("long", duration = 300.0), track("short", duration = 100.0))

        for (rules in listOf(PlaylistSortRules.SORT_NAMES, PlaylistSortRules.DISPLAY_NAMES)) {
            assertThat(tracks.sortedForListing(PlaylistSortKey.DURATION, rules).names())
                .containsExactly("short", "long").inOrder()
        }
    }

    @Test
    fun `a listing the server has sorted is kept exactly as it came`() {
        // On 2.11 the server sorts the listing itself, by names transliterated to ASCII. A
        // second sort here would undo it for any title outside plain Latin letters.
        val tracks = listOf(track("Ωmega"), track("Alpha"), track("Βeta"))

        val sorted = tracks.sortedForListing(PlaylistSortKey.NAME, PlaylistSortRules.SERVER_LISTING)

        assertThat(sorted.names()).containsExactly("Ωmega", "Alpha", "Βeta").inOrder()
    }

    @Test
    fun `the 2_10 releases are told apart by version, because all of them report schema 65`() {
        assertThat(playlistSortRulesFor(65, "2.10.6")).isEqualTo(PlaylistSortRules.SORT_NAMES)
        assertThat(playlistSortRulesFor(65, "2.10.7")).isEqualTo(PlaylistSortRules.DISPLAY_NAMES)
        assertThat(playlistSortRulesFor(65, "2.10.8")).isEqualTo(PlaylistSortRules.DISPLAY_NAMES)
        // Compared as numbers: as text, "2.10.10" would sort before "2.10.7".
        assertThat(playlistSortRulesFor(65, "2.10.10")).isEqualTo(PlaylistSortRules.DISPLAY_NAMES)
    }

    @Test
    fun `a version that cannot be read keeps the rules the app has always used`() {
        assertThat(playlistSortRulesFor(65, null)).isEqualTo(PlaylistSortRules.SORT_NAMES)
        assertThat(playlistSortRulesFor(65, "dev")).isEqualTo(PlaylistSortRules.SORT_NAMES)
        assertThat(playlistSortRulesFor(65, "0.0.0")).isEqualTo(PlaylistSortRules.SORT_NAMES)
        assertThat(playlistSortRulesFor(null, "2.10.7")).isEqualTo(PlaylistSortRules.SORT_NAMES)
    }

    @Test
    fun `the 2_11 line is told apart by schema`() {
        // 2.10.0 reports 63, 2.11.0b4 reports 85: both before PR #6906.
        assertThat(playlistSortRulesFor(63, "2.10.0")).isEqualTo(PlaylistSortRules.SORT_NAMES)
        assertThat(playlistSortRulesFor(85, "2.11.0b4")).isEqualTo(PlaylistSortRules.SORT_NAMES)
        // PR #6906 reached the development line at 87; 2.11.0b5 reports 91.
        assertThat(playlistSortRulesFor(87, "0.0.0")).isEqualTo(PlaylistSortRules.DISPLAY_NAMES)
        assertThat(playlistSortRulesFor(91, "2.11.0b5")).isEqualTo(PlaylistSortRules.DISPLAY_NAMES)
        // PR #7004 at 95; 2.11.0b6 and b7 report 99.
        assertThat(playlistSortRulesFor(95, "0.0.0")).isEqualTo(PlaylistSortRules.SERVER_LISTING)
        assertThat(playlistSortRulesFor(99, "2.11.0b7")).isEqualTo(PlaylistSortRules.SERVER_LISTING)
    }

    @Test
    fun `every order names the 2_11 listing sort the server knows`() {
        // The values of SortField and SortDirection in music_assistant_models. A field the
        // playlist listing does not offer makes the 2.11 server refuse the whole request.
        assertThat(PlaylistSortKey.POSITION.sortField).isNull()
        assertThat(
            PlaylistSortKey.entries.filter { it != PlaylistSortKey.POSITION }
                .map { "${it.sortField} ${it.sortDirection}" }
        ).containsExactly(
            "position desc", "name asc", "artist_name asc", "album_name asc", "duration asc", "duration desc"
        ).inOrder()
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
