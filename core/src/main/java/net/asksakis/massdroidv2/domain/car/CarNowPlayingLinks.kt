package net.asksakis.massdroidv2.domain.car

import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track

/**
 * The car's browse ids of an artist and an album, and the links from the playing track to them
 * (#86): a tap on the album or the artist line of the car's playing screen opens that item.
 *
 * A browse id reads "artist|<provider>|<itemId>" or "album|<provider>|<itemId>", the same ids the
 * car's lists use, so a link opens the page a list row would open.
 */
object CarNowPlayingLinks {

    /** The browse ids the playing track links to; null where there is nothing to open. */
    data class Links(val artistMediaId: String?, val albumMediaId: String?) {
        companion object {
            val None = Links(artistMediaId = null, albumMediaId = null)
        }
    }

    /** A browse id this object builds, read back. */
    data class Target(val kind: Kind, val provider: String, val itemId: String)

    enum class Kind(val prefix: String) { ARTIST("artist"), ALBUM("album") }

    private const val LIBRARY_ARTIST_PREFIX = "library://artist/"

    fun artistMediaId(provider: String, itemId: String): String = "${Kind.ARTIST.prefix}|$provider|$itemId"

    fun albumMediaId(provider: String, itemId: String): String = "${Kind.ALBUM.prefix}|$provider|$itemId"

    /**
     * The links of the playing [track]. Only a music track has them: a radio station, a podcast
     * episode or an audiobook has no artist or album page to open. A link needs a name to show
     * as well as an id. The artist is the track's first one, through its library entry when the
     * app has mapped it there, because the library entry is the one whose page lists the
     * artist's albums wherever the track came from.
     */
    fun forTrack(track: Track?): Links {
        if (track == null || track.mediaType != MediaType.TRACK) return Links.None
        val artist = track.artistNames.takeIf { it.isNotBlank() }?.let {
            val libraryId = track.artistUri?.takeIf { uri -> uri.startsWith(LIBRARY_ARTIST_PREFIX) }
                ?.removePrefix(LIBRARY_ARTIST_PREFIX)
                ?.takeIf { id -> id.isNotBlank() }
            when {
                libraryId != null -> artistMediaId("library", libraryId)
                !track.artistProvider.isNullOrBlank() && !track.artistItemId.isNullOrBlank() ->
                    artistMediaId(track.artistProvider, track.artistItemId)
                else -> null
            }
        }
        val album = track.albumName.takeIf { it.isNotBlank() }?.let {
            if (!track.albumProvider.isNullOrBlank() && !track.albumItemId.isNullOrBlank()) {
                albumMediaId(track.albumProvider, track.albumItemId)
            } else {
                null
            }
        }
        return Links(artistMediaId = artist, albumMediaId = album)
    }

    /** Reads an artist or album browse id; null for any other id. */
    fun parse(mediaId: String): Target? {
        val parts = mediaId.split("|", limit = 3)
        if (parts.size != 3) return null
        val (prefix, provider, itemId) = parts
        val kind = Kind.entries.firstOrNull { it.prefix == prefix } ?: return null
        if (provider.isBlank() || itemId.isBlank()) return null
        return Target(kind, provider, itemId)
    }
}
