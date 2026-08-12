package net.asksakis.massdroidv2.data.genre

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
import net.asksakis.massdroidv2.data.database.ArtistGenreEntity
import net.asksakis.massdroidv2.data.database.ArtistNeedingGenres
import net.asksakis.massdroidv2.data.database.GenreEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.TransactionRunner
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
    private val musicRepository: net.asksakis.massdroidv2.domain.repository.MusicRepository,
    private val transactions: TransactionRunner
) {
    data class EnrichmentProgress(
        val total: Int = 0,
        val processed: Int = 0,
        val enriched: Int = 0,
        val isRunning: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var enrichJob: Job? = null
    // Separate from [enrichJob] on purpose. The discovery sweep is bulk work that
    // can run for minutes, while enrichInBackground carries artists the user is
    // looking at RIGHT NOW (a library page, the Discover feed). Sharing one job
    // handle would make the browsing path wait behind the sweep - the queued
    // artists would sit unprocessed until it finished, because nothing restarts
    // processQueue. The MusicBrainz rate limiter still serialises the actual
    // requests, so running both costs no extra traffic.
    private var discoveryJob: Job? = null
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
        if (enrichJob?.isActive == true) {
            // A UI-driven enrichInBackground holds enrichJob, and that path never
            // schedules the discovery sweep. Returning outright here skipped
            // discovery for the WHOLE session whenever a Discover/Library page
            // happened to be enriching when the websocket connected. Discovery
            // owns a separate job and its own guard, so arm it regardless.
            scheduleDiscoveryEnrichment()
            return
        }
        enrichJob = scope.launch {
            try {
                syncLibraryArtists()
                revalidateNameResolvedIdentities()
                val allGaps = dao.getArtistsWithoutGenres()
                // An artist MusicBrainz has already said it does not know is not
                // outstanding work. Without this the enricher re-listed every one
                // of them on each start, reported hundreds to do, then zero done -
                // which reads as a broken engine rather than a settled question.
                // The resolver owns the decision so the cache-key format stays in
                // one place.
                // Keyed by name AND id: the same artist legitimately appears under
                // several uris, so two gap rows can share a name, and keying on the
                // name alone silently kept only the last of them.
                val byRef = allGaps.associateBy { MusicBrainzGenreResolver.ArtistRef(it.name, it.mbid) }
                val gaps = musicBrainzGenreResolver
                    .stillWorthAsking(byRef.keys)
                    .mapNotNull { byRef[it] }
                if (gaps.isEmpty()) {
                    // The library being settled is the NORMAL steady state, not a
                    // reason to stop: the discovery pool is where the work is once
                    // it is. Returning here left the enricher idle for good.
                    Log.d(TAG, "No artists left to enrich (${allGaps.size} already answered)")
                    scheduleDiscoveryEnrichment()
                    return@launch
                }
                Log.d(TAG, "Enriching ${gaps.size} artists (${allGaps.size - gaps.size} already answered)")
                _progress.value = EnrichmentProgress(total = gaps.size, isRunning = true)
                var enriched = 0
                var processed = 0
                for (gap in gaps) {
                    try {
                        val genres = musicBrainzGenreResolver
                            .resolve(gap.name, gap.mbid, gap.sampleTrack)
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
                scheduleDiscoveryEnrichment()
            } catch (e: Exception) {
                Log.e(TAG, "Genre enrichment failed", e)
                _progress.value = _progress.value.copy(isRunning = false)
            }
        }
    }

    /**
     * Second phase: the discovery artists the Smart Mix genre gate cannot judge.
     *
     * The library gaps above are a finite, shrinking set - on a real library the
     * enricher settles into "No artists left to enrich" and then sits idle while
     * the mix still has thousands of unjudgeable candidates. Those candidates are
     * artists the user has never played, so the library sweep never sees them and
     * only the similar-artist cache knows their names.
     *
     * The Smart Mix build used to warm these itself, twenty per run, which does
     * not work: the seed warm resolved 0 of 20 on thirteen consecutive builds in
     * 10 ms - every slot spent on artists MusicBrainz had already answered
     * "nothing" for, because the cache hides empty answers and they resurface as
     * gaps forever. Here [MusicBrainzGenreResolver.stillWorthAsking] settles that
     * question once, and the work runs outside the build so no mix ever waits.
     *
     * Runs until the queue is empty, like the library sweep above. It used to
     * stop after a fixed 300, which meant an app left open all day did nothing
     * more after the first twenty minutes and the backlog only moved when the
     * websocket reconnected. Termination is guaranteed by
     * [MusicBrainzGenreResolver.stillWorthAsking]: every artist asked is cached,
     * empty answer included, so it cannot come back in a later round.
     */
    /**
     * One-off re-check of artists whose MusicBrainz identity was decided by a bare
     * name, now that a recording can decide it properly.
     *
     * A name is not an identity. Searching "Labelle" returns the American soul group
     * "LaBelle" at score 100 and the exact "Labelle" - the Reunion Island electronic
     * producer actually in the library - at 86, so the top hit was the wrong artist
     * and no score threshold could have caught it. Those genres then became the
     * envelope of a whole Smart Mix, which filled with French chanson.
     *
     * Cached answers live for 90 days, so the improved lookup alone would not reach
     * the ones already stored (1727 of them on a real library). This clears exactly
     * those rows - name-keyed AND backed by a recording we hold - so the enricher
     * re-asks them through the recording path. Their `artist_genres` go too, because
     * the write is insert-ignore: keeping them would merge the old wrong tags with
     * the new right ones and describe the artist as both.
     *
     * Guarded by a revision flag so it runs once per bump, not on every connect.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun revalidateNameResolvedIdentities() {
        try {
            if (settingsRepository.musicBrainzIdentityRevision.first() >= MB_IDENTITY_REVISION) return
            val stale = dao.getNameResolvedArtistsWithRecording()
            if (stale.isEmpty()) {
                settingsRepository.setMusicBrainzIdentityRevision(MB_IDENTITY_REVISION)
                return
            }
            stale.chunked(DELETE_CHUNK).forEach { chunk ->
                transactions.inTransaction {
                    dao.deleteMusicBrainzTags(chunk)
                    dao.deleteArtistGenresByNames(chunk)
                }
            }
            settingsRepository.setMusicBrainzIdentityRevision(MB_IDENTITY_REVISION)
            Log.d(TAG, "Identity re-check: cleared ${stale.size} name-resolved artists for re-lookup")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Identity re-check failed: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleDiscoveryEnrichment() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            try {
                enrichDiscoveryArtists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Discovery enrichment failed", e)
            }
        }
    }

    @VisibleForTesting
    internal suspend fun enrichDiscoveryArtists() {
        // Names already attempted in THIS run. stillWorthAsking normally retires
        // an artist by caching the answer, but a failed cache write would other-
        // wise hand the same name back on the next round forever. This makes
        // progress a property of the loop itself, not of the write succeeding.
        val attempted = mutableSetOf<String>()
        var enriched = 0
        var asked = 0
        while (true) {
            val allGaps = try {
                dao.getDiscoveryArtistsWithoutGenres()
            } catch (e: Exception) {
                Log.w(TAG, "Could not list discovery artists: ${e.message}")
                return
            }
            if (allGaps.isEmpty()) break
            val byRef = allGaps.associateBy { MusicBrainzGenreResolver.ArtistRef(it.name, it.mbid) }
            val gaps = musicBrainzGenreResolver.stillWorthAsking(byRef.keys)
                .mapNotNull { byRef[it] }
                .filterNot { it.name in attempted }
            if (gaps.isEmpty()) break
            if (asked == 0) {
                Log.d(TAG, "Enriching discovery artists: ${gaps.size} to ask (${allGaps.size} unjudged in total)")
            }
            for (gap in gaps) {
                attempted += gap.name
                asked++
                try {
                    val genres = musicBrainzGenreResolver
                        .resolve(gap.name, gap.mbid, gap.sampleTrack)
                    if (genres.isNotEmpty()) {
                        writeDiscoveryArtistGenres(gap, genres)
                        enriched++
                    }
                } catch (e: CancellationException) {
                    // MUST rethrow. Swallowing it here let a cancelled sweep walk
                    // the rest of the queue, failing instantly on every entry and
                    // marking each one `attempted`, which burned the whole backlog
                    // without a single lookup.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enrich discovery artist ${gap.name}: ${e.message}")
                }
            }
        }
        if (asked == 0) {
            Log.d(TAG, "No discovery artists left to enrich")
            return
        }
        Log.d(TAG, "Discovery enrichment done: $enriched/$asked enriched")
    }

    /**
     * Genres for an artist that may have no row yet: most discovery candidates
     * have never been played, and `artist_genres` is a foreign key into
     * `artists`, so the row has to exist before the genres can land.
     */
    private suspend fun writeDiscoveryArtistGenres(gap: ArtistNeedingGenres, genres: List<String>) {
        if (gap.uri.isBlank()) return
        // All-or-nothing. These are several independent DAO writes, and a failure
        // part-way leaves the artist holding SOME genres - which is worse than
        // holding none, because both the gap query and stillWorthAsking then treat
        // them as answered and the gate judges them on a truncated tag list
        // forever.
        transactions.inTransaction {
            dao.insertArtist(ArtistEntity(uri = gap.uri, name = gap.name, mbid = gap.mbid))
            val uris = dao.getArtistUrisByName(gap.name).toMutableSet()
            uris += gap.uri
            for (genre in genres.mapNotNull { normalizeGenre(it).ifBlank { null } }) {
                dao.insertGenre(GenreEntity(name = genre))
                for (uri in uris) {
                    dao.insertArtistGenre(ArtistGenreEntity(artistUri = uri, genreName = genre))
                }
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

        /**
         * Bump when a change makes previously cached MusicBrainz identities worth
         * re-asking. 1 = recording-based disambiguation.
         */
        private const val MB_IDENTITY_REVISION = 1

        /** SQLite caps host parameters, so deletes go in chunks. */
        private const val DELETE_CHUNK = 400
    }
}
