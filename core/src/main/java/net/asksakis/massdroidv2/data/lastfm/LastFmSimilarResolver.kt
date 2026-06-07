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
import net.asksakis.massdroidv2.data.database.LastFmSimilarArtistEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class SimilarArtist(val name: String, val matchScore: Double)

@Singleton
class LastFmSimilarResolver @Inject constructor(
    private val dao: PlayHistoryDao,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "LastFmSimilar"
        private const val CACHE_DAYS = 30
        private const val CACHE_MS = CACHE_DAYS * 86_400_000L
    }

    suspend fun resolve(artistName: String, limit: Int = 20): List<SimilarArtist> {
        if (artistName.isBlank()) return emptyList()
        val apiKey = settingsRepository.lastFmApiKey.first()
        if (apiKey.isBlank()) return emptyList()

        val key = artistName.lowercase()
        val fetchedAt = dao.getSimilarArtistsFetchedAt(key)
        if (fetchedAt != null && System.currentTimeMillis() - fetchedAt < CACHE_MS) {
            return dao.getSimilarArtists(key)
                .map { SimilarArtist(it.similarArtist, it.matchScore) }
                .take(limit)
        }

        return fetchFromApi(apiKey, artistName, key, limit)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchFromApi(
        apiKey: String,
        artistName: String,
        key: String,
        limit: Int
    ): List<SimilarArtist> = withContext(Dispatchers.IO) {
        try {
            val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("method", "artist.getSimilar")
                ?.addQueryParameter("artist", artistName)
                ?.addQueryParameter("api_key", apiKey)
                ?.addQueryParameter("format", "json")
                ?.addQueryParameter("limit", "50")
                ?.build() ?: return@withContext emptyList()

            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "API error ${resp.code} for $artistName")
                    return@withContext emptyList()
                }
                resp.body?.string() ?: return@withContext emptyList()
            }
            val root = json.parseToJsonElement(body).jsonObject
            val similarArtists = root["similarartists"]?.jsonObject
                ?: return@withContext emptyList()
            val artistArray = similarArtists["artist"]?.jsonArray
                ?: return@withContext emptyList()

            val now = System.currentTimeMillis()
            val results = artistArray.mapNotNull { elem ->
                val obj = elem.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val match = obj["match"]?.jsonPrimitive?.double ?: return@mapNotNull null
                SimilarArtist(name.lowercase(), match)
            }

            val entities = results.map { similar ->
                LastFmSimilarArtistEntity(
                    sourceArtist = key,
                    similarArtist = similar.name,
                    matchScore = similar.matchScore,
                    fetchedAt = now
                )
            }
            if (entities.isNotEmpty()) {
                dao.upsertSimilarArtists(entities)
            }
            Log.d(TAG, "Resolved $artistName: ${results.size} similar artists")
            results.take(limit)
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for $artistName: ${e.message}")
            emptyList()
        }
    }
}
