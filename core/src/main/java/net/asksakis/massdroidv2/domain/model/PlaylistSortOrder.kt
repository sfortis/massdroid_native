package net.asksakis.massdroidv2.domain.model

/**
 * The orders a playlist listing can be shown in.
 *
 * These are exactly the orders Music Assistant can produce itself, named as `sort_tracks`
 * (`music_assistant/controllers/player_queues/helpers.py`) names them, and they are the same
 * set the official web UI offers for playlist tracks. Keeping the two in step is what lets
 * every Play All hand the playlist over as a container: an order the server has no key for
 * would force the app to send the expanded track list instead, which makes the server resolve
 * every track URI one by one.
 *
 * There is deliberately no reverse of name, artist or album, because the server has none. The
 * app used to offer them, along with a recently-added order of its own.
 */
enum class PlaylistSortKey(val label: String, val serverKey: String?) {
    /** The playlist's own order. The server needs no argument for it. */
    POSITION("Position", null),
    POSITION_DESC("Position, reversed", "position_desc"),
    NAME("Name", "name"),
    ARTIST("Artist", "artist"),
    ALBUM("Album", "album"),
    DURATION("Duration", "duration"),
    DURATION_DESC("Duration, longest first", "duration_desc")
}

/**
 * Order these tracks the way Music Assistant orders them itself.
 *
 * The server sorts by `sort_name` where an item has one and by the plain name otherwise, and
 * it looks at the FIRST artist alone rather than at all of them. The app used to sort by the
 * plain name and by every artist joined together, which put a track in a different place in
 * the queue than on screen for roughly one track in seven: Music Assistant drops a leading
 * article and strips diacritics when it builds a sort name, so "The Mole" sorts under M and
 * "Étude" under E. The official web UI still has that mismatch; this does not.
 *
 * A reversed order sorts rather than reverses, matching the server's own `reverse=True`, so
 * two tracks that compare equal keep the order they arrived in.
 */
fun List<Track>.sortedForListing(key: PlaylistSortKey): List<Track> = when (key) {
    PlaylistSortKey.POSITION -> this
    PlaylistSortKey.POSITION_DESC -> sortedByDescending { it.position ?: 0 }
    PlaylistSortKey.NAME -> sortedBy { it.sortName.lowercase() }
    PlaylistSortKey.ARTIST -> sortedBy { it.primaryArtistSortName.lowercase() }
    PlaylistSortKey.ALBUM -> sortedBy { it.albumSortName.lowercase() }
    PlaylistSortKey.DURATION -> sortedBy { it.duration ?: 0.0 }
    PlaylistSortKey.DURATION_DESC -> sortedByDescending { it.duration ?: 0.0 }
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
