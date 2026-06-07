package net.asksakis.massdroidv2.data.cache

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiscoverCache"
private const val CACHE_FILE = "discover_cache.json"

@Singleton
class DiscoverCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    @Serializable
    data class CacheData(
        val suggestedArtists: List<Artist> = emptyList(),
        val discoverAlbums: List<Album> = emptyList(),
        val topArtists: List<Artist> = emptyList(),
        val serverFolders: List<RecommendationFolder> = emptyList(),
        val lastRefreshed: Long = 0L
    )

    private val file: File get() = File(context.filesDir, CACHE_FILE)

    suspend fun load(): CacheData? = withContext(Dispatchers.IO) {
        try {
            val text = file.readText()
            json.decodeFromString<CacheData>(text)
        } catch (e: Exception) {
            Log.d(TAG, "No cache or corrupt: ${e.message}")
            null
        }
    }

    suspend fun save(data: CacheData) = withContext(Dispatchers.IO) {
        try {
            val stamped = data.copy(lastRefreshed = System.currentTimeMillis())
            file.writeText(json.encodeToString(CacheData.serializer(), stamped))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    fun isStale(cached: CacheData): Boolean {
        val dayMs = 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - cached.lastRefreshed > dayMs
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cache: ${e.message}")
        }
    }
}
