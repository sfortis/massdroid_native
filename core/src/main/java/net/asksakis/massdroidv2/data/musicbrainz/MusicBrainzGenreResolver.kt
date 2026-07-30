package net.asksakis.massdroidv2.data.musicbrainz

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.database.MusicBrainzArtistTagsEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Genre lookup against MusicBrainz, for artists nothing else can describe.
 *
 * Music Assistant hands back similar artists as PROVIDER items (Deezer here),
 * and those carry no genres and no MBID even when asked for individually, so
 * half to two thirds of a Smart Mix candidate pool used to reach the genre gate
 * unjudgeable. MusicBrainz covers them: on the exact artists that leaked into a
 * shoegaze mix it answered for 13 of 16, and answered correctly (Lost
 * Frequencies -> house/dance/edm, Morandi -> pop, The Telescopes -> shoegaze).
 *
 * Deliberate choices:
 * - **Search, not lookup.** The candidates have no MBID, so the entity is found
 *   by name. A hit is accepted only at [MIN_MATCH_SCORE], because a weak name
 *   match would poison the gate with a stranger's genres.
 * - **Two requests: find the entity, then read its GENRES.** The search
 *   response also carries `tags`, and using those to save a call was a mistake:
 *   tags are free-text and include nationalities, so Ramones came back as
 *   `punk rock, pop punk, punk, estados unidos` and a cluster was built on a
 *   country. The `genres` list is the curated taxonomy and gives
 *   `punk rock, pop punk, punk, power pop`.
 * - **Empty is an answer.** An artist MusicBrainz has nothing on is cached as
 *   empty, so the 1 req/s budget is never spent twice on the same dead end.
 * - Genres are taken as MusicBrainz reports them, with no allow-list. The list
 *   is a curated ~2000-entry taxonomy, not crowd tags, so there is no noise to
 *   filter out - and filtering is what made whole non-western scenes invisible.
 */
@Singleton
class MusicBrainzGenreResolver @Inject constructor(
    private val dao: PlayHistoryDao,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val rateLimiter: MusicBrainzRateLimiter
) {
    /**
     * Cached genres for [artistName]: weight-ordered, empty when MusicBrainz has
     * nothing, null when we have never asked (or the entry has expired).
     */
    suspend fun cachedGenres(artistName: String): List<String>? {
        val key = cacheKey(artistName)
        if (key.isEmpty()) return null
        val row = try {
            dao.getMusicBrainzTags(key)
        } catch (_: Exception) {
            null
        } ?: return null
        val ttl = if (row.tags.isBlank()) EMPTY_CACHE_MS else CACHE_MS
        if (System.currentTimeMillis() - row.fetchedAt > ttl) return null
        return row.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Cached genres for many artists at once, for callers that judge a whole
     * pool (the Smart Mix cluster is ~200 artists and cannot afford a query
     * each). Artists never looked up, or whose entry expired, are absent.
     */
    suspend fun cachedGenresFor(artistNames: Collection<String>): Map<String, List<String>> {
        val keys = artistNames.map { cacheKey(it) }.filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) return emptyMap()
        val rows = try {
            dao.getMusicBrainzTagsFor(keys)
        } catch (_: Exception) {
            return emptyMap()
        }
        val now = System.currentTimeMillis()
        return rows.mapNotNull { row ->
            val ttl = if (row.tags.isBlank()) EMPTY_CACHE_MS else CACHE_MS
            if (now - row.fetchedAt > ttl) return@mapNotNull null
            val tags = row.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (tags.isEmpty()) null else row.artistName to tags
        }.toMap()
    }

    /**
     * Fetch and cache genres for [artistName]. Rate-limited to MusicBrainz's
     * 1 req/s, so callers must treat this as background work, never inline in a
     * mix build.
     */
    suspend fun resolve(artistName: String): List<String> {
        val key = cacheKey(artistName)
        if (key.isEmpty()) return emptyList()
        cachedGenres(artistName)?.let { return it }
        return withContext(Dispatchers.IO) {
            val mbid = findMbid(artistName) ?: run {
                cache(key, mbid = "", genres = emptyList())
                return@withContext emptyList()
            }
            val genres = fetchGenres(mbid)
            cache(key, mbid, genres)
            genres
        }
    }

    private suspend fun cache(key: String, mbid: String, genres: List<String>) {
        try {
            dao.upsertMusicBrainzTags(
                MusicBrainzArtistTagsEntity(
                    artistName = key,
                    mbid = mbid,
                    tags = genres.joinToString(","),
                    fetchedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed for '$key': ${e.message}")
        }
    }

    private suspend fun findMbid(artistName: String): String? {
        // Quoting the name makes this a phrase match; without it Lucene splits
        // on whitespace and "Pale Saints" matches any artist called "Saints".
        val url = "$BASE/artist".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", "artist:\"$artistName\"")
            ?.addQueryParameter("fmt", "json")
            ?.addQueryParameter("limit", "1")
            ?.build() ?: return null
        val body = request(url.toString()) ?: return null
        val artist = json.parseToJsonElement(body).jsonObject["artists"]
            ?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val score = artist["score"]?.jsonPrimitive?.intOrNull ?: 0
        if (score < MIN_MATCH_SCORE) {
            Log.d(TAG, "'$artistName': best match scored $score, rejected")
            return null
        }
        return artist["id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchGenres(mbid: String): List<String> {
        val body = request("$BASE/artist/$mbid?inc=genres&fmt=json") ?: return emptyList()
        return json.parseToJsonElement(body).jsonObject["genres"]?.let { readTags(it) }.orEmpty()
    }

    /** Weight-ordered names from a MusicBrainz `tags`/`genres` array. */
    private fun readTags(element: kotlinx.serialization.json.JsonElement): List<String> =
        element.jsonArray
            .mapNotNull { entry ->
                val obj = entry.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (name.isEmpty()) null else name to (obj["count"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            .sortedByDescending { it.second }
            .take(MAX_TAGS)
            .map { it.first }

    private suspend fun request(url: String): String? {
        rateLimiter.acquire()
        return try {
            // MusicBrainz rejects requests without a descriptive User-Agent.
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $url")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
            null
        }
    }

    private fun cacheKey(artistName: String): String = artistName.trim().lowercase()

    private companion object {
        const val TAG = "MusicBrainzGenre"
        const val BASE = "https://musicbrainz.org/ws/2"
        const val USER_AGENT = "MassDroid/2.x ( https://github.com/sfortis/massdroid_native )"
        // MusicBrainz genres are stable, so entries are kept far longer than the
        // Last.fm tag cache; a miss is retried sooner in case the artist is new.
        const val CACHE_MS = 90L * 24 * 60 * 60 * 1000
        const val EMPTY_CACHE_MS = 14L * 24 * 60 * 60 * 1000
        const val MAX_TAGS = 4
        // Search scores are 0..100 and an exact name match scores 100. Anything
        // materially below that is a different artist.
        const val MIN_MATCH_SCORE = 90
    }
}
