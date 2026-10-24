package net.asksakis.massdroidv2.domain.model

/**
 * The orders a playlist listing can be shown in.
 *
 * These are exactly the orders Music Assistant can produce itself, and they are the same set
 * the official web UI offers for playlist tracks. Keeping the two in step is what lets every
 * Play All hand the playlist over as a container: an order the server has no key for would
 * force the app to send the expanded track list instead, which makes the server resolve every
 * track URI one by one.
 *
 * Each order carries two names for the server. [serverKey] is the `sort_by` key of
 * `play_media` up to Music Assistant 2.10. [sortField] and [sortDirection] are the listing
 * sort of Music Assistant 2.11, which `playlist_tracks` and `play_media` both take and which
 * replaces `sort_by` there. The playlist's own order needs neither.
 *
 * There is deliberately no reverse of name, artist or album, because the server has none. The
 * app used to offer them, along with a recently-added order of its own.
 */
enum class PlaylistSortKey(
    val label: String,
    val serverKey: String?,
    val sortField: String?,
    val sortDirection: String?
) {
    /** The playlist's own order. The server needs no argument for it. */
    POSITION("Position", null, null, null),
    POSITION_DESC("Position, reversed", "position_desc", "position", "desc"),
    NAME("Name", "name", "name", "asc"),
    ARTIST("Artist", "artist", "artist_name", "asc"),
    ALBUM("Album", "album", "album_name", "asc"),
    DURATION("Duration", "duration", "duration", "asc"),
    DURATION_DESC("Duration, longest first", "duration_desc", "duration", "desc")
}

/**
 * How the connected Music Assistant server orders a playlist it plays sorted.
 *
 * The server changed this twice in October 2026, and users run every one of these versions,
 * so the app has to follow the server it is connected to. Read it through
 * [playlistSortRulesFor].
 */
enum class PlaylistSortRules {
    /**
     * Music Assistant 2.10.6 and older. `sort_tracks` sorts by the sort name: a leading
     * article is moved to the end and diacritics are stripped.
     */
    SORT_NAMES,

    /**
     * Music Assistant 2.10.7 and later 2.10 releases (PR #6906). `sort_tracks` sorts by the
     * names the listing shows, in lower case, and keeps the disc and track order within an
     * album.
     */
    DISPLAY_NAMES,

    /**
     * Music Assistant 2.11 from schema 95 (PR #7004). The server plays a playlist exactly as
     * its own `playlist_tracks` listing sorts it, so the app asks that listing for the order
     * instead of sorting by itself. The listing compares names transliterated to ASCII, which
     * no sort on the phone could match for Greek or Cyrillic titles.
     */
    SERVER_LISTING
}

/** The first schema whose `play_media` plays a playlist in the order of its listing (PR #7004). */
private const val LISTING_SORT_MIN_SCHEMA = 95

/** The development schema at which `sort_tracks` moved to the displayed names (PR #6906). */
private const val DISPLAY_NAME_SORT_MIN_SCHEMA = 87

/** Every Music Assistant 2.10 release reports this schema, before and after PR #6906. */
private const val STABLE_2_10_SCHEMA = 65

/** The first 2.10 release whose `sort_tracks` sorts by the displayed names (PR #6906). */
private val DISPLAY_NAME_SORT_MIN_VERSION = listOf(2, 10, 7)

private val SERVER_VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)""")

/**
 * The sort rules of a server with [schemaVersion] and [serverVersion].
 *
 * The 2.10 releases cannot be told apart by schema, because 2.10.6 and 2.10.7 both report 65,
 * so the version decides there. A server whose schema or version cannot be read keeps the
 * rules the app has always used.
 */
fun playlistSortRulesFor(schemaVersion: Int?, serverVersion: String?): PlaylistSortRules {
    val schema = schemaVersion ?: return PlaylistSortRules.SORT_NAMES
    return when {
        schema >= LISTING_SORT_MIN_SCHEMA -> PlaylistSortRules.SERVER_LISTING
        schema >= DISPLAY_NAME_SORT_MIN_SCHEMA -> PlaylistSortRules.DISPLAY_NAMES
        schema == STABLE_2_10_SCHEMA && sortsByDisplayNames(serverVersion) -> PlaylistSortRules.DISPLAY_NAMES
        else -> PlaylistSortRules.SORT_NAMES
    }
}

/** Whether [serverVersion] reads as [DISPLAY_NAME_SORT_MIN_VERSION] or later. */
private fun sortsByDisplayNames(serverVersion: String?): Boolean {
    val match = serverVersion?.let { SERVER_VERSION.find(it) } ?: return false
    val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
    val difference = parts.zip(DISPLAY_NAME_SORT_MIN_VERSION).firstOrNull { (part, min) -> part != min }
    return difference == null || difference.first > difference.second
}

/**
 * Order these tracks the way the connected server orders them itself, under [rules].
 *
 * A listing the server has already sorted ([PlaylistSortRules.SERVER_LISTING]) is returned as
 * it came. A reversed order sorts rather than reverses, matching the server's own
 * `reverse=True`, so two tracks that compare equal keep the order they arrived in.
 */
fun List<Track>.sortedForListing(key: PlaylistSortKey, rules: PlaylistSortRules): List<Track> =
    when (rules) {
        PlaylistSortRules.SORT_NAMES -> sortedBySortNames(key)
        PlaylistSortRules.DISPLAY_NAMES -> sortedByDisplayNames(key)
        PlaylistSortRules.SERVER_LISTING -> this
    }

/**
 * The order of Music Assistant 2.10.6 and older.
 *
 * The server sorts by `sort_name` where an item has one and by the plain name otherwise, and
 * it looks at the FIRST artist alone rather than at all of them. The app used to sort by the
 * plain name and by every artist joined together, which put a track in a different place in
 * the queue than on screen for roughly one track in seven: Music Assistant drops a leading
 * article and strips diacritics when it builds a sort name, so "The Mole" sorts under M and
 * "Étude" under E.
 */
private fun List<Track>.sortedBySortNames(key: PlaylistSortKey): List<Track> = when (key) {
    PlaylistSortKey.NAME -> sortedBy { it.sortName.lowercase() }
    PlaylistSortKey.ARTIST -> sortedBy { it.primaryArtistSortName.lowercase() }
    PlaylistSortKey.ALBUM -> sortedBy { it.albumSortName.lowercase() }
    else -> sortedByNumbers(key)
}

/**
 * The order of Music Assistant 2.10.7 and later 2.10 releases.
 *
 * The server sorts by the names a listing shows, in lower case, so "The Mole" sorts under T
 * and "Étude" after every plain letter. The artist is still the first one alone. Within one
 * album the tracks follow their disc and track numbers.
 */
private fun List<Track>.sortedByDisplayNames(key: PlaylistSortKey): List<Track> = when (key) {
    PlaylistSortKey.NAME -> sortedBy { it.name.lowercase() }
    PlaylistSortKey.ARTIST -> sortedBy { it.primaryArtistName.lowercase() }
    PlaylistSortKey.ALBUM -> sortedWith(
        compareBy({ it.albumName.lowercase() }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 })
    )
    else -> sortedByNumbers(key)
}

/** The orders every server sorts the same way: position and duration. */
private fun List<Track>.sortedByNumbers(key: PlaylistSortKey): List<Track> = when (key) {
    PlaylistSortKey.POSITION_DESC -> sortedByDescending { it.position ?: 0 }
    PlaylistSortKey.DURATION -> sortedBy { it.duration ?: 0.0 }
    PlaylistSortKey.DURATION_DESC -> sortedByDescending { it.duration ?: 0.0 }
    PlaylistSortKey.POSITION, PlaylistSortKey.NAME, PlaylistSortKey.ARTIST, PlaylistSortKey.ALBUM -> this
}

/**
 * Read a stored sort preference, including one written before the orders were aligned with the
 * server's.
 *
 * The app used to store a key and a separate descending flag, and offered a reverse of every
 * order plus a recently-added one. A reverse that survives here becomes its own key, and the
 * orders the server cannot produce fall back to the playlist's own order.
 */
fun playlistSortKeyOf(storedKey: String, storedDescending: Boolean): PlaylistSortKey =
    when (storedKey) {
        "POSITION" -> if (storedDescending) PlaylistSortKey.POSITION_DESC else PlaylistSortKey.POSITION
        "DURATION" -> if (storedDescending) PlaylistSortKey.DURATION_DESC else PlaylistSortKey.DURATION
        else -> runCatching { PlaylistSortKey.valueOf(storedKey) }.getOrDefault(PlaylistSortKey.POSITION)
    }
