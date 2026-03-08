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

data class LastFmAlbumInfo(
    val summary: String? = null,
    val year: Int? = null
)

@Singleton
class LastFmAlbumInfoResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "LastFmAlbumInfo"
        private val YEAR_REGEX = Regex("""\b(\d{4})\b""")
    }

    private val cache = ConcurrentHashMap<String, LastFmAlbumInfo>()

    suspend fun resolve(artistName: String, albumName: String): LastFmAlbumInfo? {
        if (artistName.isBlank() || albumName.isBlank()) return null
        val apiKey = settingsRepository.lastFmApiKey.first()
        if (apiKey.isBlank()) return null

        val key = "${artistName.lowercase()}|${albumName.lowercase()}"
        cache[key]?.let { return it }

        return fetchFromApi(apiKey, artistName, albumName, key)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchFromApi(
        apiKey: String,
        artistName: String,
        albumName: String,
        key: String
    ): LastFmAlbumInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("method", "album.getInfo")
                ?.addQueryParameter("artist", artistName)
                ?.addQueryParameter("album", albumName)
                ?.addQueryParameter("api_key", apiKey)
                ?.addQueryParameter("format", "json")
                ?.build() ?: return@withContext null

            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "API error ${response.code} for $artistName - $albumName")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val root = json.parseToJsonElement(body).jsonObject
            val albumObj = root["album"]?.jsonObject ?: return@withContext null

            val wiki = albumObj["wiki"]?.jsonObject
            val summary = wiki?.get("summary")?.jsonPrimitive?.content?.trim()
                ?.replace(Regex("<a\\b[^>]*>.*?</a>"), "")
                ?.trim()
                ?.ifBlank { null }

            val published = wiki?.get("published")?.jsonPrimitive?.content?.trim()
            val year = published?.let { YEAR_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

            if (summary == null && year == null) return@withContext null

            val info = LastFmAlbumInfo(summary = summary, year = year)
            cache[key] = info
            info
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for $artistName - $albumName: ${e.message}")
            null
        }
    }
}
