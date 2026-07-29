package net.asksakis.massdroidv2.data.lastfm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.database.LastFmSimilarTrackEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** A Last.fm track-level similar result: the candidate's artist, title and match score. */
data class SimilarTrack(
    val artist: String,
    val track: String,
    val matchScore: Double
)

/**
 * Resolves track-level similars via Last.fm `track.getSimilar`. This is the
 * primary candidate source for the seed-track recommendation generator: it is
 * dramatically more genre-coherent than artist-level similarity (a synthpop
 * track returns synthpop tracks, not the artist's whole catalogue). Results are
 * cached in `lastfm_similar_tracks` with a 30-day TTL and rate-limited through
 * the shared [LastFmRateLimiter]. Mirrors [LastFmSimilarResolver].
 */
@Singleton
class LastFmTrackSimilarResolver @Inject constructor(
    private val dao: PlayHistoryDao,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
    private val rateLimiter: LastFmRateLimiter
) {
    companion object {
        private const val TAG = "LastFmTrackSimilar"
        private const val CACHE_DAYS = 30
        private const val CACHE_MS = CACHE_DAYS * 86_400_000L
        private const val API_LIMIT = 30

        /** Normalized "artist|track" key: lowercase, parens/brackets dropped, non-alnum collapsed. */
        fun sourceKey(artist: String, track: String): String =
            "${normalizeName(artist)}|${normalizeName(track)}"

        fun normalizeName(raw: String): String =
            raw.lowercase()
                .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }

    suspend fun resolve(artist: String, track: String, limit: Int = 20): List<SimilarTrack> {
        if (artist.isBlank() || track.isBlank()) return emptyList()
        val apiKey = apiKeyOrNull() ?: return emptyList()
        return cachedOrFetch(sourceKey(artist, track), limit, "$artist - $track") {
            fetchSimilarTracks(apiKey, artist, track)
        }
    }

    /**
     * An artist's most-played tracks (`artist.getTopTracks`), used as a POOL
     * WIDENER: seeds whose exact track is unknown to Last.fm (obscure covers,
     * compilation projects) return few or no track-level similars, which
     * starved mixes to well below their target. Ranked results carry a
     * rank-derived score so the caller can weight them below true track
     * similars. Shares [LastFmSimilarTrackEntity] under an `artist:` key
     * namespace (a real track key is "artist|track" and can never contain a
     * colon, so the namespaces cannot collide).
     */
    suspend fun resolveArtistTopTracks(artist: String, limit: Int = 5): List<SimilarTrack> {
        if (artist.isBlank()) return emptyList()
        val apiKey = apiKeyOrNull() ?: return emptyList()
        return cachedOrFetch("artist:${normalizeName(artist)}", limit, "top tracks of $artist") {
            fetchRankedTracks(apiKey, "artist.getTopTracks", "artist", artist, "toptracks")
        }
    }

    /**
     * The top tracks of a genre tag (`tag.getTopTracks`). Last-resort widener:
     * unlike the similar-artist path it always yields something for a mapped
     * genre, and it is in-genre by construction, so it fills a thin mix without
     * loosening coherence. Cached under a `tag:` key namespace.
     */
    suspend fun resolveTagTopTracks(tag: String, limit: Int = 30): List<SimilarTrack> {
        if (tag.isBlank()) return emptyList()
        val apiKey = apiKeyOrNull() ?: return emptyList()
        return cachedOrFetch("tag:${normalizeName(tag)}", limit, "top tracks of tag '$tag'") {
            fetchRankedTracks(apiKey, "tag.getTopTracks", "tag", tag, "tracks")
        }
    }

    /**
     * Cache-ONLY read of an artist's top tracks: returns what a previous
     * [resolveArtistTopTracks] stored, never hits the network. Lets the mix
     * generator ask "is this candidate one of the artist's well-known tracks?"
     * for free, and answer "don't know" rather than pay an API call per artist.
     */
    suspend fun cachedArtistTopTracks(artist: String): List<SimilarTrack>? {
        if (artist.isBlank()) return null
        val key = "artist:${normalizeName(artist)}"
        val fetchedAt = dao.getSimilarTracksFetchedAt(key) ?: return null
        if (System.currentTimeMillis() - fetchedAt >= CACHE_MS) return null
        return dao.getSimilarTracks(key)
            .filter { it.similarArtist.isNotBlank() && it.similarTrack.isNotBlank() }
            .map { SimilarTrack(it.similarArtist, it.similarTrack, it.matchScore) }
    }

    private suspend fun apiKeyOrNull(): String? =
        settingsRepository.lastFmApiKey.first().takeIf { it.isNotBlank() }

    // Shared cache envelope for every Last.fm track list we persist: serve from
    // the table while the TTL holds, otherwise fetch and write back. An empty
    // result writes a single blank sentinel row so getSimilarTracksFetchedAt is
    // set and the TTL applies to empties too (niche seeds are not re-queried
    // every mix); the sentinel is filtered out on read.
    private suspend fun cachedOrFetch(
        key: String,
        limit: Int,
        label: String,
        fetch: suspend () -> List<SimilarTrack>
    ): List<SimilarTrack> {
        val fetchedAt = dao.getSimilarTracksFetchedAt(key)
        if (fetchedAt != null && System.currentTimeMillis() - fetchedAt < CACHE_MS) {
            return dao.getSimilarTracks(key)
                .filter { it.similarArtist.isNotBlank() && it.similarTrack.isNotBlank() }
                .map { SimilarTrack(it.similarArtist, it.similarTrack, it.matchScore) }
                .take(limit)
        }
        val results = fetch()
        val now = System.currentTimeMillis()
        val entities = results.ifEmpty { listOf(SimilarTrack("", "", 0.0)) }.map { s ->
            LastFmSimilarTrackEntity(
                sourceKey = key,
                similarArtist = s.artist,
                similarTrack = s.track,
                matchScore = s.matchScore,
                fetchedAt = now
            )
        }
        dao.upsertSimilarTracks(entities)
        Log.d(TAG, "Resolved $label: ${results.size} tracks")
        return results.take(limit)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchSimilarTracks(
        apiKey: String,
        artist: String,
        track: String
    ): List<SimilarTrack> = withContext(Dispatchers.IO) {
        try {
            val url = apiUrl(apiKey, "track.getSimilar") {
                addQueryParameter("artist", artist)
                addQueryParameter("track", track)
                addQueryParameter("limit", API_LIMIT.toString())
            } ?: return@withContext emptyList()
            val root = getJson(url, "$artist - $track") ?: return@withContext emptyList()
            val similar = root["similartracks"]?.jsonObject ?: return@withContext emptyList()
            val trackArray = similar["track"]?.jsonArray ?: return@withContext emptyList()
            trackArray.mapNotNull { elem ->
                val obj = elem.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val artistName = obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val match = obj["match"]?.jsonPrimitive?.double ?: 0.0
                SimilarTrack(artistName, name, match)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for $artist - $track: ${e.message}")
            emptyList()
        }
    }

    // artist.getTopTracks / tag.getTopTracks share a shape: a ranked track array
    // with no match score. Rank position becomes the score (1.0 for the first,
    // tapering down) so downstream weighting stays uniform with track.getSimilar.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchRankedTracks(
        apiKey: String,
        method: String,
        paramName: String,
        paramValue: String,
        rootKey: String
    ): List<SimilarTrack> = withContext(Dispatchers.IO) {
        try {
            val url = apiUrl(apiKey, method) {
                addQueryParameter(paramName, paramValue)
                addQueryParameter("limit", API_LIMIT.toString())
            } ?: return@withContext emptyList()
            val root = getJson(url, "$method $paramValue") ?: return@withContext emptyList()
            val container = root[rootKey]?.jsonObject ?: return@withContext emptyList()
            val trackArray = container["track"]?.jsonArray ?: return@withContext emptyList()
            val total = trackArray.size.coerceAtLeast(1)
            trackArray.mapIndexedNotNull { index, elem ->
                val obj = elem.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val artistName = obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    ?: return@mapIndexedNotNull null
                SimilarTrack(artistName, name, 1.0 - index.toDouble() / total)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for $method $paramValue: ${e.message}")
            emptyList()
        }
    }

    private fun apiUrl(
        apiKey: String,
        method: String,
        params: okhttp3.HttpUrl.Builder.() -> Unit
    ): okhttp3.HttpUrl? = "https://ws.audioscrobbler.com/2.0/".toHttpUrlOrNull()
        ?.newBuilder()
        ?.apply {
            addQueryParameter("method", method)
            params()
            addQueryParameter("api_key", apiKey)
            addQueryParameter("format", "json")
        }
        ?.build()

    private suspend fun getJson(url: okhttp3.HttpUrl, label: String): JsonObject? {
        val request = Request.Builder().url(url).build()
        rateLimiter.acquire()
        val body = okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "API error ${resp.code} for $label")
                return null
            }
            resp.body?.string() ?: return null
        }
        return json.parseToJsonElement(body).jsonObject
    }
}
