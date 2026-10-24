package net.asksakis.massdroidv2.data.musicbrainz

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.asksakis.massdroidv2.data.database.AlbumInfoEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A few words about an album, with no API key: the album's MusicBrainz release group, its
 * Wikidata or Wikipedia link, and the article's summary. The same chain as
 * [ArtistBioResolver], one level down.
 *
 * Music Assistant describes an album only when one of its metadata providers knows it, and
 * a provider album (most of a Deezer library) usually has nothing. Well-known albums have
 * an article, for example "Warpaint is the second studio album by the American indie rock
 * band Warpaint, released on January 17, 2014 on Rough Trade Records..." Small releases
 * have none and are not in MusicBrainz at all, and for them this answers null.
 *
 * Answers, a miss included, are kept in `album_info` for 30 days, so an album played again
 * does not walk the chain again.
 */
@Singleton
class AlbumInfoResolver @Inject constructor(
    private val dao: PlayHistoryDao,
    private val json: Json,
    private val rateLimiter: MusicBrainzRateLimiter,
    private val wikipedia: WikipediaSummaries,
) {

    /**
     * The summary of the album [albumName] by [artistName], or null when nothing describes
     * it. [albumType] (Music Assistant's: album, single, ep, compilation) and [year] narrow
     * the match.
     */
    suspend fun resolve(artistName: String, albumName: String, albumType: String?, year: Int?): String? {
        val key = cacheKey(artistName, albumName)
        if (key.isEmpty()) return null
        cached(key)?.let { return it.ifEmpty { null } }
        return withContext(Dispatchers.IO) {
            when (val outcome = describe(artistName, albumName, albumType, year)) {
                is Outcome.Found -> outcome.text.also { store(key, it) }
                Outcome.Nothing -> null.also { store(key, "") }
                // Not cached: the next play of the album asks again.
                Outcome.Failed -> null
            }
        }
    }

    /** What the chain found, told apart from a chain that could not finish. */
    private sealed interface Outcome {
        data class Found(val text: String) : Outcome
        data object Nothing : Outcome
        data object Failed : Outcome
    }

    private suspend fun describe(artistName: String, albumName: String, albumType: String?, year: Int?): Outcome {
        val url = "$MB_BASE/release-group".toHttpUrlOrNull()?.newBuilder()
            // Phrase queries on both names, so "Love Is to Die" is not matched word by word.
            ?.addQueryParameter(
                "query",
                "releasegroup:\"${luceneEscape(ReleaseGroupMatch.withoutProviderSuffix(albumName))}\" " +
                    "AND artist:\"${luceneEscape(artistName)}\""
            )
            ?.addQueryParameter("limit", SEARCH_CANDIDATES.toString())
            ?.addQueryParameter("fmt", "json")
            ?.build() ?: return Outcome.Nothing
        val search = when (val fetched = mbFetch(url.toString())) {
            is WikipediaSummaries.Fetched.Body -> fetched.text
            WikipediaSummaries.Fetched.Missing -> return Outcome.Nothing
            is WikipediaSummaries.Fetched.Failed -> return Outcome.Failed
        }
        val group = ReleaseGroupMatch.pick(
            ReleaseGroupMatch.parse(json, search), artistName, albumName, albumType, year
        ) ?: return Outcome.Nothing
        val details = when (val fetched = mbFetch("$MB_BASE/release-group/${group.id}?inc=url-rels&fmt=json")) {
            is WikipediaSummaries.Fetched.Body -> fetched.text
            WikipediaSummaries.Fetched.Missing -> return Outcome.Nothing
            is WikipediaSummaries.Fetched.Failed -> return Outcome.Failed
        }
        val relations = runCatching { json.parseToJsonElement(details).jsonObject["relations"]?.jsonArray }
            .getOrNull()
        return when (val summary = wikipedia.summaryFor(relations, MIN_MEANINGFUL_SUMMARY)) {
            is WikipediaSummaries.Summary.Text -> Outcome.Found(summary.text)
            WikipediaSummaries.Summary.None -> Outcome.Nothing
            WikipediaSummaries.Summary.Failed -> Outcome.Failed
        }
    }

    /**
     * A MusicBrainz GET through the shared gate. A 503 holds every MusicBrainz caller off,
     * as the genre resolver's do: the limit is per address, and the TV's own genre
     * enrichment runs at the same time.
     */
    private suspend fun mbFetch(url: String): WikipediaSummaries.Fetched {
        rateLimiter.acquire()
        val fetched = wikipedia.fetch(url)
        when {
            fetched is WikipediaSummaries.Fetched.Failed && fetched.rateLimited ->
                rateLimiter.backOff(fetched.retryAfterSeconds)
            fetched is WikipediaSummaries.Fetched.Body -> rateLimiter.noteSuccess()
        }
        return fetched
    }

    private suspend fun cached(key: String): String? {
        val row = try {
            dao.getAlbumInfo(key)
        } catch (e: Exception) {
            Log.w(TAG, "album info cache read failed for '$key': ${e.message}")
            null
        } ?: return null
        if (System.currentTimeMillis() - row.fetchedAt > CACHE_MS) return null
        return row.text
    }

    private suspend fun store(key: String, text: String) {
        val now = System.currentTimeMillis()
        try {
            dao.upsertAlbumInfo(AlbumInfoEntity(albumKey = key, text = text, fetchedAt = now))
            dao.deleteExpiredAlbumInfo(now - CACHE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "album info cache write failed for '$key': ${e.message}")
        }
    }

    private fun luceneEscape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun cacheKey(artistName: String, albumName: String): String {
        val artist = ReleaseGroupMatch.comparisonKey(artistName)
        val album = ReleaseGroupMatch.comparisonKey(albumName)
        return if (artist.isEmpty() || album.isEmpty()) "" else "$artist|$album"
    }

    private companion object {
        const val TAG = "AlbumInfo"
        const val MB_BASE = "https://musicbrainz.org/ws/2"

        /** Enough to get past same-named releases of other kinds; the Warpaint case needed two. */
        const val SEARCH_CANDIDATES = 10

        /** Thirty days, found or not, as the owner asked (2026-10-23). */
        const val CACHE_MS = 30L * 24 * 60 * 60 * 1000

        /** Shorter than the artist bio's bar: an album's summary is often two sentences. */
        const val MIN_MEANINGFUL_SUMMARY = 100
    }
}
