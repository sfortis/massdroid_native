package net.asksakis.massdroidv2.domain.model

/**
 * An order a library listing can be asked for.
 *
 * [apiValue] is the Music Assistant `order_by` key. [defaultDescending] is the direction the
 * field is normally read in, applied when the listener picks it: names and durations run
 * upwards, times and counts start from the most recent or the highest.
 *
 * Not every option fits every tab, because `order_by` becomes an SQL ORDER BY over the media
 * type's own table. Ask [sortOptionsFor] rather than offering the whole enum.
 */
enum class SortOption(
    val apiValue: String,
    val label: String,
    val defaultDescending: Boolean = false,
    /**
     * Whether the server has a reverse of this key. `random` has none: `random_desc` is not in
     * the server's SORT_KEYS, and an unknown key leaves the query with no ORDER BY at all.
     */
    val reversible: Boolean = true
) {
    NAME("name", "Name"),
    ALBUM_ARTIST("album_artist_name", "Album Artist"),
    TRACK_ARTIST("track_artist_name", "Artist"),
    DURATION("duration", "Duration"),
    YEAR("year", "Year", defaultDescending = true),
    RECENTLY_ADDED("timestamp_added", "Recently Added", defaultDescending = true),
    RECENTLY_MODIFIED("timestamp_modified", "Recently Modified", defaultDescending = true),
    LAST_PLAYED("last_played", "Last Played", defaultDescending = true),
    MOST_PLAYED("play_count", "Most Played", defaultDescending = true),
    RANDOM("random", "Random", reversible = false);

    /** The `order_by` to send, reversed only where the server has a key for the reverse. */
    fun orderBy(descending: Boolean): String =
        if (descending && reversible) "${apiValue}_desc" else apiValue
}

/**
 * The orders this tab can be asked for.
 *
 * Music Assistant turns `order_by` into an SQL ORDER BY over the media type's own table, so a
 * key naming a column that table does not have fails the whole request with error 999, "no such
 * column", and the tab comes back empty. Checked against 2.10.4: only albums carry `year`,
 * radios and podcasts carry no `duration`, and the two artist orders read `artists.search_name`
 * through a join that only albums and tracks make. Browse is a folder listing the app orders
 * itself, so it offers the name alone.
 */
fun sortOptionsFor(tab: LibraryTabKey): List<SortOption> = when (tab) {
    LibraryTabKey.BROWSE -> listOf(SortOption.NAME)
    LibraryTabKey.ALBUMS -> listOf(
        SortOption.NAME, SortOption.ALBUM_ARTIST, SortOption.YEAR, SortOption.RECENTLY_ADDED,
        SortOption.LAST_PLAYED, SortOption.MOST_PLAYED, SortOption.RANDOM
    )
    LibraryTabKey.TRACKS -> listOf(
        SortOption.NAME, SortOption.TRACK_ARTIST, SortOption.DURATION, SortOption.RECENTLY_ADDED,
        SortOption.LAST_PLAYED, SortOption.MOST_PLAYED, SortOption.RANDOM
    )
    LibraryTabKey.PLAYLISTS -> listOf(
        SortOption.NAME, SortOption.RECENTLY_ADDED, SortOption.RECENTLY_MODIFIED,
        SortOption.LAST_PLAYED, SortOption.MOST_PLAYED, SortOption.RANDOM
    )
    LibraryTabKey.AUDIOBOOKS -> listOf(
        SortOption.NAME, SortOption.DURATION, SortOption.RECENTLY_ADDED,
        SortOption.LAST_PLAYED, SortOption.MOST_PLAYED, SortOption.RANDOM
    )
    LibraryTabKey.ARTISTS, LibraryTabKey.RADIOS, LibraryTabKey.PODCASTS -> listOf(
        SortOption.NAME, SortOption.RECENTLY_ADDED, SortOption.LAST_PLAYED,
        SortOption.MOST_PLAYED, SortOption.RANDOM
    )
}

/**
 * The order to use for [tab], given what was [stored] for it.
 *
 * A build that offered an option on more tabs than this one does can leave a stored order the
 * server would refuse, and the tab would then show nothing at all. Falling back to the name
 * costs the listener their choice on that one tab and keeps the listing readable.
 */
fun sortOptionFor(tab: LibraryTabKey, stored: SortOption?): SortOption =
    if (stored != null && stored in sortOptionsFor(tab)) stored else SortOption.NAME

enum class LibraryDisplayMode {
    LIST, GRID
}

enum class LibraryTabKey(
    val index: Int,
    val defaultDisplayMode: LibraryDisplayMode,
    /**
     * The server's own name for what this tab holds, so an event that names a media type can
     * be matched to the one list it changed. Browse holds no single kind and has none.
     */
    val mediaTypeName: String? = null
) {
    ARTISTS(index = 0, defaultDisplayMode = LibraryDisplayMode.GRID, mediaTypeName = "artist"),
    ALBUMS(index = 1, defaultDisplayMode = LibraryDisplayMode.GRID, mediaTypeName = "album"),
    TRACKS(index = 2, defaultDisplayMode = LibraryDisplayMode.GRID, mediaTypeName = "track"),
    PLAYLISTS(index = 3, defaultDisplayMode = LibraryDisplayMode.GRID, mediaTypeName = "playlist"),
    RADIOS(index = 4, defaultDisplayMode = LibraryDisplayMode.GRID, mediaTypeName = "radio"),
    AUDIOBOOKS(index = 5, defaultDisplayMode = LibraryDisplayMode.LIST, mediaTypeName = "audiobook"),
    PODCASTS(index = 6, defaultDisplayMode = LibraryDisplayMode.GRID, mediaTypeName = "podcast"),
    BROWSE(index = 7, defaultDisplayMode = LibraryDisplayMode.LIST);

    companion object {
        fun fromIndex(index: Int): LibraryTabKey? = entries.firstOrNull { it.index == index }

        fun fromStoredKey(key: String): LibraryTabKey? =
            entries.firstOrNull { it.name == key }
                ?: key.toIntOrNull()?.let(::fromIndex)
    }
}

/**
 * Which kinds of playlist the library shows.
 *
 * [SMART] is anything the server rebuilds when it is played (`is_dynamic`): the Smart
 * Playlists plugin, the built-in Infinite Mixes, and whatever else Music Assistant adds
 * later. Keyed on that rather than on a provider name so a new generator needs no code.
 *
 * The filter runs in the app, because `library_items` offers no server-side equivalent.
 */
enum class PlaylistTypeFilter(val label: String) {
    ALL("All"),
    NORMAL("Normal"),
    SMART("Smart")
}
