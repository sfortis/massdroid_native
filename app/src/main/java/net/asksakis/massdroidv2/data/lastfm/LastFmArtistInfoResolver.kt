package net.asksakis.massdroidv2.data.lastfm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LastFmArtistInfoResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "LastFmArtistInfo"
    }

    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolve(artistName: String): String? {
        if (artistName.isBlank()) return null
        val apiKey = settingsRepository.lastFmApiKey.first()
        if (apiKey.isBlank()) return null

        val key = artistName.lowercase()
        cache[key]?.let { return it }

        return fetchFromApi(apiKey, artistName, key)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchFromApi(
        apiKey: String,
        artistName: String,
        key: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("method", "artist.getInfo")
                ?.addQueryParameter("artist", artistName)
                ?.addQueryParameter("api_key", apiKey)
                ?.addQueryParameter("format", "json")
                ?.build() ?: return@withContext null

            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "API error ${response.code} for $artistName")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val root = json.parseToJsonElement(body).jsonObject
            val bio = root["artist"]?.jsonObject?.get("bio")?.jsonObject ?: return@withContext null
            val summary = bio["summary"]?.jsonPrimitive?.content?.trim()
                ?.replace(Regex("<a\\b[^>]*>.*?</a>"), "")
                ?.trim()
                ?.ifBlank { null }
                ?: return@withContext null

            cache[key] = summary
            summary
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for $artistName: ${e.message}")
            null
        }
    }
}
