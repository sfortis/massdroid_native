package net.asksakis.massdroidv2.domain.recommendation

import net.asksakis.massdroidv2.domain.model.Track

/**
 * The artists the listener blocked, as the mix generators test them.
 *
 * A block is stored under the artist URIs it was made from, so a URI match is the reliable
 * test. The name is the fallback for the same artist reached under another URI, such as its
 * library item or another provider. Names are compared per artist through
 * [normalizeArtistKey], which makes "Étienne Daho" and "Etienne Daho" the same artist.
 */
class BlockedArtists(uris: Collection<String>, names: Collection<String>) {

    private val uriKeys: Set<String> =
        uris.mapNotNullTo(HashSet()) { MediaIdentity.canonicalArtistKey(uri = it) }
    private val nameKeys: Set<String> =
        names.map(::normalizeArtistKey).filterTo(HashSet()) { it.isNotBlank() }

    fun isEmpty(): Boolean = uriKeys.isEmpty() && nameKeys.isEmpty()

    /** Whether the artist known by this [uri] or this [name] is blocked. */
    fun blocksArtist(uri: String?, name: String): Boolean =
        uri?.let { MediaIdentity.canonicalArtistKey(uri = it) } in uriKeys ||
            normalizeArtistKey(name) in nameKeys

    /**
     * Whether any artist credited on [track] is blocked.
     *
     * A track by several artists carries their names joined with ", ", so each name is tested
     * on its own. The whole string is tested as well, for an artist whose own name holds a comma.
     */
    fun blocksAnyArtistOf(track: Track): Boolean {
        if (isEmpty()) return false
        val uris = track.artistUris + listOfNotNull(track.artistUri)
        if (uris.any { MediaIdentity.canonicalArtistKey(uri = it) in uriKeys }) return true
        if (nameKeys.isEmpty()) return false
        val names = track.artistNames.split(ARTIST_NAME_SEPARATOR) + track.artistNames
        return names.any { normalizeArtistKey(it) in nameKeys }
    }

    companion object {
        val NONE = BlockedArtists(emptySet(), emptySet())

        // How the repositories join the artist names of a track.
        private const val ARTIST_NAME_SEPARATOR = ", "
    }
}
