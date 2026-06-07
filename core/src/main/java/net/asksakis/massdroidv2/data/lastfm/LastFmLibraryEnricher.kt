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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LastFmLibraryEnricher @Inject constructor(
    private val lastFmGenreResolver: LastFmGenreResolver,
    private val dao: PlayHistoryDao,
    private val settingsRepository: net.asksakis.massdroidv2.domain.repository.SettingsRepository,
    private val musicRepository: net.asksakis.massdroidv2.domain.repository.MusicRepository
) {
    data class EnrichmentProgress(
        val total: Int = 0,
        val processed: Int = 0,
        val enriched: Int = 0,
        val isRunning: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var enrichJob: Job? = null
    private val enrichedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingQueue = ConcurrentLinkedQueue<Artist>()
    private val _progress = MutableStateFlow(EnrichmentProgress())
    val progress: StateFlow<EnrichmentProgress> = _progress.asStateFlow()

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

    @Suppress("TooGenericExceptionCaught")
    fun enrichAllUnenriched() {
        if (enrichJob?.isActive == true) return
        enrichJob = scope.launch {
            try {
                val apiKey = settingsRepository.lastFmApiKey.first()
                if (apiKey.isBlank()) {
                    Log.d(TAG, "No Last.fm API key, skipping periodic enrichment")
                    return@launch
                }
                syncLibraryArtists()
                val allArtists = dao.getAllArtistNames()
                val unenriched = allArtists.filter { it.isNotBlank() && it !in enrichedNames }
                var enriched = 0
                var processed = 0
                _progress.value = EnrichmentProgress(total = unenriched.size, isRunning = true)
                for (name in unenriched) {
                    val cached = dao.getLastFmTags(name)
                    if (cached != null) {
                        enrichedNames += name
                        if (cached.tags.isNotBlank()) {
                            val uris = dao.getArtistUrisByName(name)
                            val existingGenres = uris.flatMap { dao.getGenresForArtist(it) }.toSet()
                            if (existingGenres.isEmpty()) {
                                val normalizedGenres = cached.tags.split(",")
                                    .mapNotNull { normalizeGenre(it).ifBlank { null } }
                                for (genre in normalizedGenres) {
                                    dao.insertGenre(GenreEntity(name = genre))
                                    for (uri in uris) {
                                        dao.insertArtistGenre(ArtistGenreEntity(artistUri = uri, genreName = genre))
                                    }
                                }
                                if (normalizedGenres.isNotEmpty()) enriched++
                            }
                        }
                        processed++
                        _progress.value = _progress.value.copy(processed = processed)
                        continue
                    }
                    try {
                        val tags = lastFmGenreResolver.resolve(name)
                        enrichedNames += name
                        if (tags.isNotEmpty()) {
                            val uris = dao.getArtistUrisByName(name)
                            val normalizedGenres = tags.mapNotNull { normalizeGenre(it).ifBlank { null } }
                            for (genre in normalizedGenres) {
                                dao.insertGenre(GenreEntity(name = genre))
                                for (uri in uris) {
                                    dao.insertArtistGenre(ArtistGenreEntity(artistUri = uri, genreName = genre))
                                }
                            }
                            enriched++
                        }
                        delay(BULK_RATE_LIMIT_MS)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enrich $name: ${e.message}")
                    }
                    processed++
                    _progress.value = _progress.value.copy(processed = processed, enriched = enriched)
                }
                Log.d(TAG, "Periodic enrichment done: $enriched/${unenriched.size} enriched")
                _progress.value = EnrichmentProgress(total = unenriched.size, processed = processed, enriched = enriched, isRunning = false)
            } catch (e: Exception) {
                Log.e(TAG, "Periodic enrichment failed", e)
                _progress.value = _progress.value.copy(isRunning = false)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun syncLibraryArtists() {
        try {
            val existing = dao.getAllArtistNames().toSet()
            var offset = 0
            var inserted = 0
            while (true) {
                val batch = musicRepository.getArtists(limit = PAGE_SIZE, offset = offset, orderBy = "name")
                if (batch.isEmpty()) break
                for (artist in batch) {
                    if (artist.name.isNotBlank() && artist.name !in existing) {
                        dao.insertArtist(ArtistEntity(uri = artist.uri, name = artist.name))
                        inserted++
                    }
                }
                offset += batch.size
                if (batch.size < PAGE_SIZE) break
            }
            if (inserted > 0) Log.d(TAG, "Synced $inserted new library artists")
        } catch (e: Exception) {
            Log.w(TAG, "Library artist sync failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LastFmEnricher"
        private const val PAGE_SIZE = 500
        private const val RATE_LIMIT_MS = 200L
        private const val BULK_RATE_LIMIT_MS = 500L
    }
}
