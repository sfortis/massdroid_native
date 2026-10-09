package net.asksakis.massdroidv2.data.genre

import android.util.Log
import kotlinx.coroutines.CancellationException
import net.asksakis.massdroidv2.domain.model.ServerGenre
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.genreKey
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a Library search matches by genre, from both sources the app has.
 *
 * [localArtistKeys] are the library artists whose genres in the app's database (MusicBrainz)
 * match, as canonical artist keys. [serverGenreIds] are the server's own genres (MA 2.10+)
 * whose name matches; the server maps artists, and a few albums and tracks, to them.
 */
data class LibraryGenreMatch(
    val localArtistKeys: Set<String>,
    val serverGenreIds: List<Int>
) {
    val isEmpty: Boolean get() = localArtistKeys.isEmpty() && serverGenreIds.isEmpty()
}

/**
 * Finds the genres a Library search names. The server's genre list is matched on the genre's
 * name, not its aliases: the server's own search also reads aliases, and "metal" then returned
 * Funk, Rock, Punk and Trap through aliases such as "funk metal".
 */
@Singleton
class LibraryGenreSearch @Inject constructor(
    private val genreRepository: GenreRepository,
    private val musicRepository: MusicRepository
) {
    @Volatile
    private var cachedServerGenres: CachedGenres? = null

    /** The genre match for [query], or null when neither source has a genre that matches. */
    suspend fun match(query: String): LibraryGenreMatch? {
        val queryKey = genreKey(query)
        if (queryKey.isEmpty()) return null
        val localUris = try {
            genreRepository.searchArtistUris(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Local genre search failed: ${e.message}")
            emptyList()
        }
        val localKeys = localUris.mapNotNullTo(HashSet()) { MediaIdentity.artistKeyFromUri(it) }
        val serverIds = serverGenres().filter { genreKey(it.name).contains(queryKey) }.map { it.id }
        return LibraryGenreMatch(localKeys, serverIds).takeUnless { it.isEmpty }
    }

    /**
     * The whole result of a Library search for [query] in one tab, or null when [query] names no
     * genre or the tab is larger than [MAX_SCANNED_ITEMS], in which case the caller runs its
     * usual paged name search.
     *
     * [fetchPage] reads one page of the tab in the listener's sort and filters, with an optional
     * name search and an optional list of server genre ids. The whole tab is read in that sort and
     * kept to the name matches, the items the server maps to a matching genre, and the items whose
     * artist matches ([artistKeysOf]). With [matchServerGenreArtists] the artists the server maps
     * to the genre count as matching artists too, which albums and tracks need because the server
     * maps almost none of them directly.
     */
    suspend fun <T> searchTab(
        query: String,
        fetchPage: suspend (search: String?, genreIds: List<Int>?, limit: Int, offset: Int) -> List<T>,
        uriOf: (T) -> String,
        artistKeysOf: (T) -> Collection<String>,
        matchServerGenreArtists: Boolean
    ): List<T>? {
        val match = match(query) ?: return null
        val ordered = fetchAll { limit, offset -> fetchPage(null, null, limit, offset) } ?: return null
        val nameHits = fetchAll { limit, offset -> fetchPage(query, null, limit, offset) }
            .orEmpty().mapTo(HashSet(), uriOf)
        val ids = match.serverGenreIds
        val serverHits = if (ids.isEmpty()) {
            emptySet()
        } else {
            fetchAll { limit, offset -> fetchPage(null, ids, limit, offset) }.orEmpty().mapTo(HashSet(), uriOf)
        }
        val artistKeys = if (matchServerGenreArtists && ids.isNotEmpty()) {
            match.localArtistKeys + serverGenreArtistKeys(ids)
        } else {
            match.localArtistKeys
        }
        return keepGenreSearchMatches(ordered, uriOf, artistKeysOf, nameHits, serverHits, artistKeys)
    }

    private suspend fun serverGenreArtistKeys(ids: List<Int>): Set<String> =
        fetchAll { limit, offset -> musicRepository.getArtists(limit = limit, offset = offset, genreIds = ids) }
            .orEmpty()
            .mapNotNullTo(HashSet()) { MediaIdentity.artistKeyFromUri(it.uri) }

    /** Every page of [fetchPage], or null once more than [MAX_SCANNED_ITEMS] have come back. */
    private suspend fun <T> fetchAll(fetchPage: suspend (limit: Int, offset: Int) -> List<T>): List<T>? {
        val all = ArrayList<T>()
        while (true) {
            val page = fetchPage(SCAN_PAGE_SIZE, all.size)
            all += page
            if (all.size > MAX_SCANNED_ITEMS) return null
            if (page.size < SCAN_PAGE_SIZE) return all
        }
    }

    /**
     * The server's genres, cached for [SERVER_GENRES_TTL_MS] because they change only when
     * someone edits them. A server without genres (older than MA 2.10) answers with an error,
     * which leaves the server side out and is not cached, so a later search asks again.
     */
    private suspend fun serverGenres(): List<ServerGenre> {
        val now = System.currentTimeMillis()
        cachedServerGenres?.takeIf { now - it.fetchedAt < SERVER_GENRES_TTL_MS }?.let { return it.genres }
        return try {
            musicRepository.getServerGenres().also { cachedServerGenres = CachedGenres(it, now) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Server genres unavailable: ${e.message}")
            emptyList()
        }
    }

    private class CachedGenres(val genres: List<ServerGenre>, val fetchedAt: Long)

    private companion object {
        const val TAG = "LibraryGenreSearch"
        const val SERVER_GENRES_TTL_MS = 30 * 60 * 1000L

        /** The server's own default and maximum page for `library_items`. */
        const val SCAN_PAGE_SIZE = 500

        /**
         * A tab larger than this is not read whole for a genre search: the search falls back to
         * the server's paged name search, so a very large library never reads thousands of items
         * on every keystroke.
         */
        const val MAX_SCANNED_ITEMS = 5000
    }
}

/**
 * The items of [ordered], in its order, that a genre search keeps: a name match the server
 * returned ([nameHits]), an item the server maps to a matching genre ([serverGenreHits]), or an
 * item with an artist in [artistKeys]. [ordered] is the whole tab in the listener's sort, so the
 * result keeps that sort whichever source matched.
 */
fun <T> keepGenreSearchMatches(
    ordered: List<T>,
    uriOf: (T) -> String,
    artistKeysOf: (T) -> Collection<String>,
    nameHits: Set<String>,
    serverGenreHits: Set<String>,
    artistKeys: Set<String>
): List<T> = ordered.filter { item ->
    val uri = uriOf(item)
    uri in nameHits || uri in serverGenreHits || artistKeysOf(item).any { it in artistKeys }
}
