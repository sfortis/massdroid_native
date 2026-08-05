package net.asksakis.massdroidv2.data.genre

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
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
                val allGaps = dao.getArtistsWithoutGenres()
                // An artist MusicBrainz has already said it does not know is not
                // outstanding work. Without this the enricher re-listed every one
                // of them on each start, reported hundreds to do, then zero done -
                // which reads as a broken engine rather than a settled question.
                // The resolver owns the decision so the cache-key format stays in
                // one place.
                val byName = allGaps.associateBy { it.name }
                val gaps = musicBrainzGenreResolver
                    .stillWorthAsking(allGaps.map { MusicBrainzGenreResolver.ArtistRef(it.name, it.mbid) })
                    .mapNotNull { byName[it.name] }
                if (gaps.isEmpty()) {
                    Log.d(TAG, "No artists left to enrich (${allGaps.size} already answered)")
                    return@launch
                }
                Log.d(TAG, "Enriching ${gaps.size} artists (${allGaps.size - gaps.size} already answered)")
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
    /**
     * Bring the local artist rows in line with the server's library.
     *
     * Music Assistant REUSES library ids: remove an artist and the next one
     * added can take the freed `library://artist/<n>`. Measured against a real
     * server, 113 of 164 library uris pointed at a different artist locally than
     * on the server - `library://artist/41` was stored as Savages while the
     * server had Lindstrøm there.
     *
     * That is not a cosmetic mismatch. Everything downstream keys off the uri:
     * asking for `similar_artists` on artist/41 returns Lindstrøm's neighbours
     * while the mix believes it is expanding a post-punk seed, and the stale
     * genres attached to the uri describe the previous occupant. So a uri whose
     * occupant changed is repointed and its genres are dropped, to be rebuilt
     * from MusicBrainz for whoever lives there now.
     */
    private suspend fun syncLibraryArtists() {
        try {
            var offset = 0
            var inserted = 0
            var repointed = 0
            val listed = mutableSetOf<String>()
            while (true) {
                val batch = musicRepository.getArtists(limit = PAGE_SIZE, offset = offset, orderBy = "name")
                if (batch.isEmpty()) break
                val known = dao.getArtistsByUris(batch.map { it.uri }).associateBy { it.uri }
                for (artist in batch) {
                    if (artist.name.isBlank()) continue
                    listed += artist.uri
                    val local = known[artist.uri]
                    when {
                        local == null -> {
                            // Inserted whether or not the name is already known
                            // under some other uri. One artist legitimately has
                            // several - `library://artist/59` and
                            // `deezer://artist/1201` are the same person - and
                            // the library one is the one that matters: it is
                            // what resolves provider uris back to the library,
                            // and only library items answer `similar_artists`.
                            // Skipping it on a name collision left the artist
                            // reachable solely through the weaker
                            // `similar_tracks` route (measured against a real
                            // server: 1 of 167 library artists, Rae & Christian,
                            // known locally only as a Deezer uri).
                            dao.insertArtist(
                                ArtistEntity(uri = artist.uri, name = artist.name, mbid = artist.mbid)
                            )
                            inserted++
                        }
                        // Same uri, different occupant: the id was recycled.
                        !local.name.equals(artist.name, ignoreCase = true) -> {
                            Log.d(TAG, "Library id reused: ${artist.uri} was '${local.name}', now '${artist.name}'")
                            dao.replaceArtistIdentity(artist.uri, artist.name, artist.mbid)
                            dao.deleteArtistGenres(artist.uri)
                            repointed++
                        }
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
            repointed += verifyUnlistedLibraryArtists(listed)
            if (inserted > 0 || repointed > 0) {
                Log.d(TAG, "Library sync: $inserted new, $repointed uris repointed to a new artist")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Library artist sync failed: ${e.message}")
        }
    }

    /**
     * Check the library uris the artist listing never returns.
     *
     * `music/artists/library_items` does not list every artist reachable under a
     * `library://artist/<id>` uri: measured against a real server it answered
     * with 166 artists while `get_artist` happily resolved ids outside that set.
     * Those ids are exactly the ones that go stale unnoticed, because the paging
     * loop above can never reach them - on that server `library://artist/190`
     * was stored locally as one artist while the server had another there.
     *
     * So anything we hold locally but the listing did not mention is confirmed
     * one id at a time. Only stale rows cost a round-trip after the first pass,
     * since a row that already agrees with the server is left alone.
     */
    private suspend fun verifyUnlistedLibraryArtists(listed: Set<String>): Int {
        val unlisted = dao.getLibraryArtistUris().filter { it.uri !in listed }
        if (unlisted.isEmpty()) return 0
        var repointed = 0
        for (row in unlisted.take(UNLISTED_VERIFY_LIMIT)) {
            val itemId = row.uri.substringAfterLast('/').takeIf { it.isNotBlank() } ?: continue
            val server = try {
                musicRepository.getArtist(itemId, "library", lazy = true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not verify ${row.uri}: ${e.message}")
                null
            } ?: continue
            if (server.name.isBlank() || server.name.equals(row.name, ignoreCase = true)) continue
            Log.d(TAG, "Unlisted library id reused: ${row.uri} was '${row.name}', now '${server.name}'")
            dao.replaceArtistIdentity(row.uri, server.name, server.mbid)
            dao.deleteArtistGenres(row.uri)
            repointed++
        }
        return repointed
    }

    companion object {
        private const val TAG = "GenreEnricher"
        private const val PAGE_SIZE = 500
        // Bounded so a large library cannot turn one sync into hundreds of
        // round-trips; the rest are picked up by later syncs.
        private const val UNLISTED_VERIFY_LIMIT = 60
    }
}
