package net.asksakis.massdroidv2.data.genre

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
class LibraryGenreEnricher @Inject constructor(
    private val musicBrainzGenreResolver: net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver,
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
                // The resolver answers from its own cache when it can, and the
                // MusicBrainz rate limiter only paces real calls.
                val tags = musicBrainzGenreResolver.resolve(name, artist.mbid)
                enrichedNames += name
                if (tags.isNotEmpty()) {
                    writeArtistGenres(artist, tags)
                    enriched++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enrich ${artist.name}: ${e.message}")
            }
        }
        if (total > 0) {
            Log.d(TAG, "Background enrichment done: $enriched/$total enriched")
        }
    }

    /** Gap walk: the DB row is already there, only the genres are missing. */
    private suspend fun writeArtistGenres(artistName: String, genres: List<String>) {
        val uris = dao.getArtistUrisByName(artistName)
        if (uris.isEmpty()) return
        for (genre in genres.mapNotNull { normalizeGenre(it).ifBlank { null } }) {
            dao.insertGenre(GenreEntity(name = genre))
            for (uri in uris) {
                dao.insertArtistGenre(ArtistGenreEntity(artistUri = uri, genreName = genre))
            }
        }
    }

    private suspend fun writeArtistGenres(artist: Artist, genres: List<String>) {
        val primaryUri = artist.canonicalKey() ?: return
        dao.insertArtist(ArtistEntity(uri = primaryUri, name = artist.name, mbid = artist.mbid))
        artist.mbid?.let { dao.setArtistMbidIfMissing(artist.name, it) }
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

    /**
     * Fill in genres for the artists that have none, from MusicBrainz.
     *
     * Only the GAPS are walked, not the whole library. MusicBrainz allows one
     * request per second, so on a real history (5574 artists, 539 of them
     * without a genre) working through everything would take hours of radio
     * time, while the gaps take minutes - and the artists that already have
     * genres have them from Music Assistant, which is a better source than a
     * name lookup anyway.
     *
     * An artist whose MBID we know (Music Assistant reports one for most library
     * items) is looked up by id, which is exact; the rest fall back to a name
     * search inside the resolver.
     */
    @Suppress("TooGenericExceptionCaught")
    fun enrichAllUnenriched() {
        if (enrichJob?.isActive == true) return
        enrichJob = scope.launch {
            try {
                syncLibraryArtists()
                val gaps = dao.getArtistsWithoutGenres()
                if (gaps.isEmpty()) {
                    Log.d(TAG, "No artists without genres")
                    return@launch
                }
                Log.d(TAG, "Enriching ${gaps.size} artists that have no genres yet")
                _progress.value = EnrichmentProgress(total = gaps.size, isRunning = true)
                var enriched = 0
                var processed = 0
                for (gap in gaps) {
                    try {
                        val genres = musicBrainzGenreResolver.resolve(gap.name, gap.mbid)
                        if (genres.isNotEmpty()) {
                            writeArtistGenres(gap.name, genres)
                            enriched++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enrich ${gap.name}: ${e.message}")
                    }
                    processed++
                    _progress.value = _progress.value.copy(processed = processed, enriched = enriched)
                }
                Log.d(TAG, "Genre enrichment done: $enriched/${gaps.size} enriched")
                _progress.value = EnrichmentProgress(
                    total = gaps.size, processed = processed, enriched = enriched, isRunning = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Genre enrichment failed", e)
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
                        dao.insertArtist(
                            ArtistEntity(uri = artist.uri, name = artist.name, mbid = artist.mbid)
                        )
                        inserted++
                    }
                    if (artist.mbid != null) {
                        // Fill the id in for artists stored before we read it, and
                        // for ones first seen through playback (which has none).
                        dao.setArtistMbidIfMissing(artist.name, artist.mbid)
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
        private const val TAG = "GenreEnricher"
        private const val PAGE_SIZE = 500
    }
}
