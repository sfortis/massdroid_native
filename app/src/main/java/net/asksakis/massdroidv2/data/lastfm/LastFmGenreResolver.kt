package net.asksakis.massdroidv2.data.lastfm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.database.LastFmArtistTagsEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LastFmGenreResolver @Inject constructor(
    private val dao: PlayHistoryDao,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "LastFmGenreResolver"
        private const val CACHE_DAYS = 30
        private const val EMPTY_CACHE_HOURS = 48
        private const val MIN_TAG_COUNT = 10
        private const val MAX_TAGS = 3
        private const val CACHE_MS = CACHE_DAYS * 86_400_000L
        private const val EMPTY_CACHE_MS = EMPTY_CACHE_HOURS * 3_600_000L

        private val ALLOWED_GENRES: Set<String> = setOf(
            "acid jazz", "acoustic", "alt country", "alternative",
            "alternative metal", "alternative rock", "ambient", "americana",
            "art rock", "atmospheric black metal", "avant garde",
            "ballad", "baroque", "black metal", "blues", "blues rock",
            "breakbeat", "breakcore", "britpop", "brutal death metal",
            "celtic", "chillout", "classic rock", "classical", "club",
            "contemporary classical", "country",
            "dance", "dark ambient", "dark electro", "darkwave",
            "death metal", "deathcore", "deep house",
            "depressive black metal", "disco", "doom metal", "downtempo",
            "dream pop", "drone", "drum and bass", "dub", "dubstep",
            "easy listening", "ebm", "electro", "electronic", "electronica",
            "electropop", "emo", "ethereal", "experimental",
            "folk", "folk metal", "folk rock", "funk", "fusion",
            "garage rock", "glam rock", "glitch", "goth",
            "gothic metal", "gothic rock", "grindcore", "grunge",
            "hard rock", "hardcore", "hardcore punk", "heavy metal",
            "hip hop", "house", "idm",
            "indie", "indie folk", "indie pop", "indie rock",
            "industrial", "industrial metal", "industrial rock", "instrumental",
            "jazz", "jazz fusion", "krautrock",
            "latin", "lo fi", "lounge",
            "mathcore", "melodic death metal", "melodic hardcore",
            "melodic metal", "metal", "metalcore", "minimal", "mpb",
            "neoclassical", "neofolk", "new age", "new wave",
            "noise", "noise rock", "nu jazz", "nu metal",
            "pop", "pop punk", "pop rock", "post hardcore",
            "post metal", "post punk", "post rock", "power metal",
            "progressive", "progressive metal", "progressive rock",
            "progressive trance", "psychedelic", "psychedelic rock", "psytrance",
            "punk", "punk rock",
            "rap", "reggae", "rhythm and blues", "rnb", "rock", "rockabilly",
            "screamo", "shoegaze", "singer songwriter", "ska", "sludge",
            "smooth jazz", "soft rock", "soul", "soundtrack",
            "southern rock", "space rock", "speed metal", "stoner rock",
            "swing", "symphonic metal", "synth pop", "synthpop",
            "tech house", "technical death metal", "techno",
            "thrash metal", "trance", "trip hop",
            "underground hip hop", "viking metal", "world"
        )

        private fun normalizeTag(tag: String): String =
            net.asksakis.massdroidv2.domain.recommendation.normalizeGenre(tag).replace('-', ' ')
    }

    suspend fun resolve(artistName: String): List<String> {
        if (artistName.isBlank()) return emptyList()
        val apiKey = settingsRepository.lastFmApiKey.first()
        if (apiKey.isBlank()) return emptyList()

        val cached = dao.getLastFmTags(artistName)
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.fetchedAt
            val ttl = if (cached.tags.isBlank()) EMPTY_CACHE_MS else CACHE_MS
            if (age < ttl) {
                return cached.tags.split(",").filter { it.isNotBlank() }
            }
        }

        return fetchFromApi(apiKey, artistName)
    }

    /**
     * Returns null on success, or an error description on failure.
     */
    suspend fun validateApiKey(apiKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("method", "artist.getTopTags")
                ?.addQueryParameter("artist", "Radiohead")
                ?.addQueryParameter("api_key", apiKey)
                ?.addQueryParameter("format", "json")
                ?.build() ?: return@withContext "Invalid URL"

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                Log.d(TAG, "API key validation: ${response.code}")
                when {
                    response.isSuccessful -> null
                    response.code == 403 -> "Key not recognized by Last.fm. Make sure you copied the API Key (not the Shared Secret)."
                    response.code == 429 -> "Last.fm rate limit. Wait a minute and try again."
                    else -> "Last.fm returned HTTP ${response.code}"
                }
            }
        } catch (e: java.net.UnknownHostException) {
            "Cannot reach Last.fm. Check your internet connection."
        } catch (e: Exception) {
            Log.w(TAG, "API key validation failed: ${e.message}")
            "Connection failed: ${e.message}"
        }
    }

    private suspend fun fetchFromApi(apiKey: String, artistName: String): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("method", "artist.getTopTags")
                    ?.addQueryParameter("artist", artistName)
                    ?.addQueryParameter("api_key", apiKey)
                    ?.addQueryParameter("format", "json")
                    ?.build() ?: return@withContext emptyList()

                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                val body = response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Last.fm API error ${resp.code} for $artistName")
                        return@withContext emptyList()
                    }
                    resp.body?.string() ?: return@withContext emptyList()
                }
                val root = json.parseToJsonElement(body).jsonObject
                val toptags = root["toptags"]?.jsonObject ?: return@withContext emptyList()
                val tagArray = toptags["tag"]?.jsonArray ?: return@withContext emptyList()

                val seen = mutableSetOf<String>()
                val tags = tagArray
                    .mapNotNull { elem ->
                        val obj = elem.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content
                            ?: return@mapNotNull null
                        val count = obj["count"]?.jsonPrimitive?.int ?: 0
                        if (count < MIN_TAG_COUNT) return@mapNotNull null
                        val normalized = normalizeTag(name)
                        if (normalized !in ALLOWED_GENRES) return@mapNotNull null
                        if (!seen.add(normalized)) return@mapNotNull null
                        normalized
                    }
                    .take(MAX_TAGS)

                dao.upsertLastFmTags(
                    LastFmArtistTagsEntity(
                        artistName = artistName,
                        tags = tags.joinToString(","),
                        fetchedAt = System.currentTimeMillis()
                    )
                )
                Log.d(TAG, "Resolved $artistName: $tags")
                tags
            } catch (e: Exception) {
                Log.w(TAG, "Last.fm fetch failed for $artistName: ${e.message}")
                emptyList()
            }
        }
}
