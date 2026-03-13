package net.asksakis.massdroidv2.data.lastfm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.ArtistGenreEntity
import net.asksakis.massdroidv2.data.database.GenreEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.recommendation.canonicalKey
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LastFmLibraryEnricher @Inject constructor(
    private val lastFmGenreResolver: LastFmGenreResolver,
    private val dao: PlayHistoryDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var enrichJob: Job? = null
    private val enrichedNames = mutableSetOf<String>()
    private val pendingQueue = ConcurrentLinkedQueue<Artist>()

    @Suppress("TooGenericExceptionCaught")
    fun enrichInBackground(artists: List<Artist>) {
        val newArtists = artists.filter { it.name.trim().let { n -> n.isNotBlank() && n !in enrichedNames } }
        if (newArtists.isEmpty()) return
        pendingQueue.addAll(newArtists)
        if (enrichJob?.isActive == true) return
        enrichJob = scope.launch {
            try {
                processQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Background enrichment failed", e)
            }
        }
    }

    private suspend fun processQueue() {
        dao.backfillArtistGenres()
        Log.d(TAG, "Backfill completed")
        var enriched = 0
        var total = 0
        while (true) {
            val artist = pendingQueue.poll() ?: break
            val name = artist.name.trim()
            if (name.isBlank() || name in enrichedNames) continue
            total++
            try {
                val cached = dao.getLastFmTags(name)
                if (cached != null) {
                    enrichedNames += name
                    writeArtistGenres(artist, cached.tags.split(",").filter { it.isNotBlank() })
                    continue
                }
                val tags = lastFmGenreResolver.resolve(name)
                enrichedNames += name
                if (tags.isNotEmpty()) {
                    writeArtistGenres(artist, tags)
                    enriched++
                }
                delay(RATE_LIMIT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enrich ${artist.name}: ${e.message}")
            }
        }
        if (total > 0) {
            Log.d(TAG, "Background enrichment done: $enriched/$total enriched")
        }
    }

    private suspend fun writeArtistGenres(artist: Artist, genres: List<String>) {
        val primaryUri = artist.canonicalKey() ?: return
        dao.insertArtist(ArtistEntity(uri = primaryUri, name = artist.name))
        val allUris = dao.getArtistUrisByName(artist.name).toMutableSet()
        allUris += primaryUri
        val normalizedGenres = genres.mapNotNull { normalizeGenre(it).ifBlank { null } }
        for (genre in normalizedGenres) {
            dao.insertGenre(GenreEntity(name = genre))
            for (uri in allUris) {
                dao.insertArtistGenre(ArtistGenreEntity(artistUri = uri, genreName = genre))
            }
        }
    }

    companion object {
        private const val TAG = "LastFmEnricher"
        private const val RATE_LIMIT_MS = 200L
    }
}
