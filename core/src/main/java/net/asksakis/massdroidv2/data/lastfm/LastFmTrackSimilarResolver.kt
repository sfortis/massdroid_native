package net.asksakis.massdroidv2.data.lastfm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
        val apiKey = settingsRepository.lastFmApiKey.first()
        if (apiKey.isBlank()) return emptyList()

        val key = sourceKey(artist, track)
        val fetchedAt = dao.getSimilarTracksFetchedAt(key)
        if (fetchedAt != null && System.currentTimeMillis() - fetchedAt < CACHE_MS) {
            // A lone sentinel row (blank artist/track) marks a known-empty
            // result so the TTL applies to empties too; filter it out on read.
            return dao.getSimilarTracks(key)
                .filter { it.similarArtist.isNotBlank() && it.similarTrack.isNotBlank() }
                .map { SimilarTrack(it.similarArtist, it.similarTrack, it.matchScore) }
                .take(limit)
        }

        return fetchFromApi(apiKey, artist, track, key, limit)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchFromApi(
        apiKey: String,
        artist: String,
        track: String,
        key: String,
        limit: Int
    ): List<SimilarTrack> = withContext(Dispatchers.IO) {
        try {
            val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("method", "track.getSimilar")
                ?.addQueryParameter("artist", artist)
                ?.addQueryParameter("track", track)
                ?.addQueryParameter("api_key", apiKey)
                ?.addQueryParameter("format", "json")
                ?.addQueryParameter("limit", API_LIMIT.toString())
                ?.build() ?: return@withContext emptyList()

            val request = Request.Builder().url(url).build()
            rateLimiter.acquire()
            val response = okHttpClient.newCall(request).execute()
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "API error ${resp.code} for $artist - $track")
                    return@withContext emptyList()
                }
                resp.body?.string() ?: return@withContext emptyList()
            }
            val root = json.parseToJsonElement(body).jsonObject
            val similar = root["similartracks"]?.jsonObject ?: return@withContext emptyList()
            val trackArray = similar["track"]?.jsonArray ?: return@withContext emptyList()

            val now = System.currentTimeMillis()
            val results = trackArray.mapNotNull { elem ->
                val obj = elem.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val artistName = obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val match = obj["match"]?.jsonPrimitive?.double ?: 0.0
                SimilarTrack(artistName, name, match)
            }

            // Cache the result. An empty result writes a single blank sentinel
            // row so getSimilarTracksFetchedAt is set and the 30-day TTL applies
            // to empties too (niche seeds are not re-queried every mix). The
            // sentinel is filtered out on read.
            val entities = if (results.isEmpty()) {
                listOf(
                    LastFmSimilarTrackEntity(
                        sourceKey = key,
                        similarArtist = "",
                        similarTrack = "",
                        matchScore = 0.0,
                        fetchedAt = now
                    )
                )
            } else {
                results.map { s ->
                    LastFmSimilarTrackEntity(
                        sourceKey = key,
                        similarArtist = s.artist,
                        similarTrack = s.track,
                        matchScore = s.matchScore,
                        fetchedAt = now
                    )
                }
            }
            dao.upsertSimilarTracks(entities)
            Log.d(TAG, "Resolved $artist - $track: ${results.size} similar tracks")
            results.take(limit)
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for $artist - $track: ${e.message}")
            emptyList()
        }
    }
}
