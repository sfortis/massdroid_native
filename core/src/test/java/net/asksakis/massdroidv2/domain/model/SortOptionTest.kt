package net.asksakis.massdroidv2.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the library sort keys to what Music Assistant accepts.
 *
 * `order_by` becomes an SQL ORDER BY over the media type's own table, so a key the table has no
 * column for fails the whole listing with error 999 and the tab comes back empty. The sets below
 * were read from the server's `SORT_KEYS` and confirmed against a live 2.10.4 server: `duration`
 * answers for tracks and audiobooks but not for radios or podcasts, `year` only for albums, and
 * `random_desc` is not a key at all.
 */
class SortOptionTest {

    /** `SORT_KEYS` in `music_assistant/controllers/music/media/base.py`, server 2.10.4. */
    private val serverSortKeys = setOf(
        "name", "name_desc", "duration", "duration_desc", "sort_name", "sort_name_desc",
        "timestamp_added", "timestamp_added_desc", "timestamp_modified", "timestamp_modified_desc",
        "last_played", "last_played_desc", "play_count", "play_count_desc", "year", "year_desc",
        "position", "position_desc", "album_artist_name", "album_artist_name_desc",
        "track_artist_name", "track_artist_name_desc", "random", "random_play_count"
    )

    @Test
    fun `every order the app offers is a key the server knows`() {
        SortOption.entries.forEach { option ->
            assertThat(serverSortKeys).contains(option.orderBy(descending = false))
            assertThat(serverSortKeys).contains(option.orderBy(descending = true))
        }
    }

    @Test
    fun `random is never reversed`() {
        assertThat(SortOption.RANDOM.orderBy(descending = true)).isEqualTo("random")
        assertThat(SortOption.RANDOM.reversible).isFalse()
    }

    @Test
    fun `a reversible order carries the descending suffix`() {
        assertThat(SortOption.NAME.orderBy(descending = true)).isEqualTo("name_desc")
        assertThat(SortOption.LAST_PLAYED.orderBy(descending = false)).isEqualTo("last_played")
    }

    @Test
    fun `duration is offered only where the table has that column`() {
        listOf(LibraryTabKey.TRACKS, LibraryTabKey.AUDIOBOOKS).forEach { tab ->
            assertThat(sortOptionsFor(tab)).contains(SortOption.DURATION)
        }
        listOf(LibraryTabKey.RADIOS, LibraryTabKey.PODCASTS, LibraryTabKey.ARTISTS).forEach { tab ->
            assertThat(sortOptionsFor(tab)).doesNotContain(SortOption.DURATION)
        }
    }

    @Test
    fun `the album and track artist orders stay on their own tab`() {
        assertThat(sortOptionsFor(LibraryTabKey.ALBUMS)).contains(SortOption.ALBUM_ARTIST)
        assertThat(sortOptionsFor(LibraryTabKey.TRACKS)).contains(SortOption.TRACK_ARTIST)
        assertThat(sortOptionsFor(LibraryTabKey.ALBUMS)).doesNotContain(SortOption.TRACK_ARTIST)
        assertThat(sortOptionsFor(LibraryTabKey.ARTISTS)).doesNotContain(SortOption.ALBUM_ARTIST)
    }

    @Test
    fun `year is albums only and modified is playlists only`() {
        assertThat(sortOptionsFor(LibraryTabKey.ALBUMS)).contains(SortOption.YEAR)
        assertThat(sortOptionsFor(LibraryTabKey.TRACKS)).doesNotContain(SortOption.YEAR)
        assertThat(sortOptionsFor(LibraryTabKey.PLAYLISTS)).contains(SortOption.RECENTLY_MODIFIED)
        assertThat(sortOptionsFor(LibraryTabKey.ALBUMS)).doesNotContain(SortOption.RECENTLY_MODIFIED)
    }

    @Test
    fun `browse offers the name alone because the app orders that listing itself`() {
        assertThat(sortOptionsFor(LibraryTabKey.BROWSE)).containsExactly(SortOption.NAME)
    }

    @Test
    fun `a stored order the tab cannot ask for falls back to the name`() {
        assertThat(sortOptionFor(LibraryTabKey.RADIOS, SortOption.DURATION))
            .isEqualTo(SortOption.NAME)
        assertThat(sortOptionFor(LibraryTabKey.TRACKS, SortOption.DURATION))
            .isEqualTo(SortOption.DURATION)
        assertThat(sortOptionFor(LibraryTabKey.ALBUMS, null)).isEqualTo(SortOption.NAME)
    }

    @Test
    fun `picking an order starts it in the direction that field is read in`() {
        listOf(SortOption.NAME, SortOption.ALBUM_ARTIST, SortOption.TRACK_ARTIST, SortOption.DURATION)
            .forEach { assertThat(it.defaultDescending).isFalse() }
        listOf(
            SortOption.YEAR, SortOption.RECENTLY_ADDED, SortOption.RECENTLY_MODIFIED,
            SortOption.LAST_PLAYED, SortOption.MOST_PLAYED
        ).forEach { assertThat(it.defaultDescending).isTrue() }
    }
}
