package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.genre.GenreRepository
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmSimilarResolver
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "MixOrchestrator"

private const val MAX_GENRE_RADIO_ARTIST_URIS = 30
private const val GENRE_RADIO_ATTEMPT_POOL_SIZE = 20
private val GENRE_RADIO_BATCH_SIZES = listOf(12, 8, 4, 2, 1)
private const val GENRE_RADIO_EXPLORATION_COUNT = 4
private const val GENRE_RADIO_ALLOWED_DECADE_GAP = 10
private const val GENRE_RADIO_DECADE_LOOKBACK_DAYS = 720
private const val ARTIST_DECADE_LOOKBACK_DAYS = 720
private const val SMART_MIX_DAYPART_LOOKBACK_DAYS = 180
private const val SMART_MIX_RECENT_LOOKBACK_DAYS = 14
private const val SMART_MIX_TARGET_MIN = 20
private const val SMART_MIX_TARGET_SPAN = 40
private const val DISCOVERY_ADJACENT_THRESHOLD = 0.34
private const val FALLBACK_GENRE_BREADTH = 6
private const val DISCOVERY_EXPANSION_THRESHOLD = 0.66
private const val MIX_MAX_TRACKS_PER_ARTIST = 2
private const val DAYPART_GENRE_BOOST_WEIGHT = 2.0
private const val SMART_MIX_MIN_TRACKS = 8
private const val SMART_MIX_HISTORY_DEPTH = 3
private const val SMART_MIX_LASTFM_EXPANSION = 12
private const val SMART_MIX_LASTFM_SEED_LIMIT = 5
private const val SMART_MIX_LASTFM_SIMILARS_PER_SEED = 20
private const val SMART_MIX_SEARCH_LIMIT = 5
private const val SMART_MIX_MAX_SEARCHES = 8
private const val SMART_MIX_SEARCH_TIMEOUT_MS = 4000L
private const val RESOLVED_ARTIST_TTL_MS = 30L * 24 * 60 * 60 * 1000
private const val RECENT_ARTIST_APPEARANCE_PENALTY = 1.0
private const val RECENT_ARTIST_HISTORY_DEPTH = 4
private const val RECENT_GENRE_EXCLUSION_DEPTH = 3
private const val SMART_MIX_FAVORITES_QUERY_LIMIT = 500
private const val ARTIST_TRACK_CACHE_TTL_MS = 12 * 60 * 60 * 1000L
private const val MAX_TRACKS_PER_ARTIST = 40
private const val ARTIST_FETCH_CONCURRENCY = 6
private const val PREFETCH_CONCURRENCY = 2
private const val GENRE_RADIO_DISCOVERY_SEEDS = 10
private const val GENRE_RADIO_SIMILAR_RESOLVE_LIMIT = 5
private const val GENRE_RADIO_START_WAIT_TIMEOUT_MS = 8_000L
private const val BLL_ARTIST_SCORE_LIMIT = 500
private const val GENRE_RADIO_MIN_STRICT_CANDIDATES = 4
private const val GENRE_RADIO_METADATA_ERROR_CODE = 999
// Self-loaded library context (car path) is reused within this window so a Smart
// Mix immediately followed by a Genre Radio does not re-run the full library load.
private const val CONTEXT_SELF_LOAD_TTL_MS = 30_000L

private data class SmartMixResult(val tracks: List<Track>, val genre: String?)

/**
 * Headless Smart Mix + Genre Radio build+play orchestration, shared by the phone
 * Discover screen (UI) and the headless AAOS car (no UI). Lifted verbatim out of
 * DiscoverViewModel so both front-ends produce the same mix; the only seams are:
 *  - the queue/player id is supplied by the caller (the car has no "selected
 *    player"; it passes the Sendspin player id),
 *  - the network-derived library context (artist/genre maps) may be supplied by
 *    the caller to avoid a redundant load (the phone already has it loaded for the
 *    Discover screen); when null, the orchestrator self-loads it (the car path),
 *  - results are returned as [MixResult] for the caller to surface (UI snackbar
 *    on the phone, log on the car), instead of writing UI state directly.
 *
 * @Singleton so the recent-mix cool-down history is shared in-process and survives
 * across taps (better variety), and so a single instance serves both front-ends.
 */
@Singleton
class MixPlaybackOrchestrator @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val playHistoryRepository: PlayHistoryRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val seedTrackMixGenerator: SeedTrackMixGenerator,
    private val mixEngine: MixEngine,
    private val genreRepository: GenreRepository,
    private val lastFmSimilarResolver: LastFmSimilarResolver,
    private val lastFmGenreResolver: LastFmGenreResolver,
    private val sessionEventBus: SessionEventBus,
) {

    /**
     * Network-derived library maps the genre-engine fallback needs. The phone
     * already maintains these for the Discover screen and passes them in (no extra
     * load); the car passes null and the orchestrator self-loads them.
     */
    data class LibraryContext(
        val artistByUri: Map<String, Artist>,
        val genreArtists: Map<String, List<String>>,
        val strictGenreArtists: Map<String, List<String>>,
    )

    sealed interface MixResult {
        /** Smart Mix or finite seed-track Genre Radio: the queue was replaced with [count] tracks. */
        data class Played(val count: Int, val genre: String?) : MixResult
        /** Genre Radio server-radio fallback was accepted and playback started (open-ended, no count). */
        data object Started : MixResult
        /** Smart Mix: too little listening data for a solid mix. */
        data object NotEnoughData : MixResult
        /** Genre Radio: no in-library artists match the genre. */
        data object NoMatchingArtists : MixResult
        /** No queue/player id was available. */
        data object NoPlayer : MixResult
        /** Another mix build is already in flight. */
        data object Busy : MixResult
        /** Genre Radio server-radio fallback did not confirm a start in time. */
        data object StartTimeout : MixResult
        data class Failed(val message: String) : MixResult
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Single-flight: a second build request while one is running returns Busy.
    private val buildMutex = Mutex()

    // ---- Library context (supplied by the phone, or self-loaded for the car) ----
    private val contentLoader = DiscoverContentLoader(musicRepository, genreRepository)
    private var artistByUri = emptyMap<String, Artist>()
    private var genreArtists = mapOf<String, List<String>>()
    private var strictGenreArtists = mapOf<String, List<String>>()
    private var selfLoadedContextAtMs = 0L
    private var selfLoadedExcluded = emptySet<String>()

    // ---- BLL / decade scoring (self-loaded from local DB, cached) ----
    private var bllArtistScoreMap = emptyMap<String, Double>()
    private var artistDominantDecades = emptyMap<String, Int>()

    // ---- Smart-filter snapshot (refreshed per build) ----
    private var smartArtistScoreMap = emptyMap<String, Double>()
    private var excludedArtistUris = emptySet<String>()
    private var excludedTrackUris = emptySet<String>()
    private var blockedArtistNames = emptySet<String>()

    // ---- Recent-mix cool-down history (shared across taps for variety) ----
    private val recentSmartMixHistory: ArrayDeque<Set<String>> = ArrayDeque()
    private val recentSmartMixArtists: ArrayDeque<Set<String>> = ArrayDeque()
    private val recentSmartMixGenres: ArrayDeque<String> = ArrayDeque()

    // ---- Tuning knobs (read fresh from settings per build) ----
    private var smartMixVariety = 0.5
    private var smartMixDiscovery = 0.5
    private var smartMixLength = 0.5
    private var smartMixStrictness = 0.5

    @Volatile private var expansionPrefetchJob: Job? = null
    // The coroutine running the current build (the caller's launch). Held so an
    // account switch can cancel an in-flight build before wiping its state, the
    // way the old DiscoverViewModel cancelled its radioStartJob on reset.
    @Volatile private var activeBuildJob: Job? = null

    init {
        scope.launch {
            sessionEventBus.resets.collect { resetForAccountSwitch() }
        }
    }

    private suspend fun resetForAccountSwitch() {
        Log.d(TAG, "Session reset: dropping in-memory mix state")
        // Cancel AND join the in-flight build first so it cannot read these maps
        // mid-clear (or replace the new account's queue with the old account's mix).
        activeBuildJob?.cancelAndJoin()
        expansionPrefetchJob?.cancel()
        artistByUri = emptyMap()
        genreArtists = emptyMap()
        strictGenreArtists = emptyMap()
        selfLoadedContextAtMs = 0L
        selfLoadedExcluded = emptySet()
        bllArtistScoreMap = emptyMap()
        artistDominantDecades = emptyMap()
        smartArtistScoreMap = emptyMap()
        excludedArtistUris = emptySet()
        excludedTrackUris = emptySet()
        blockedArtistNames = emptySet()
        recentSmartMixHistory.clear()
        recentSmartMixArtists.clear()
        recentSmartMixGenres.clear()
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    suspend fun playSmartMix(queueId: String, context: LibraryContext? = null): MixResult {
        if (queueId.isBlank()) return MixResult.NoPlayer
        if (!buildMutex.tryLock()) return MixResult.Busy
        activeBuildJob = currentCoroutineContext()[Job]
        try {
            refreshTuning()
            refreshSmartFiltersForMix()
            ensureContext(context)
            ensureBllArtistScoresLoaded()
            ensureArtistDecadesLoaded()
            val mixResult = buildSmartMixTracks()
            if (mixResult.tracks.size < SMART_MIX_MIN_TRACKS) return MixResult.NotEnoughData
            playGeneratedMix(queueId, mixResult.tracks, mixResult.genre, "smartMix")
            return MixResult.Played(mixResult.tracks.size, mixResult.genre)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "playSmartMix failed", e)
            return MixResult.Failed("Failed to generate smart mix")
        } finally {
            activeBuildJob = null
            buildMutex.unlock()
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun playGenreRadio(queueId: String, genre: String, context: LibraryContext? = null): MixResult {
        if (queueId.isBlank()) return MixResult.NoPlayer
        if (!buildMutex.tryLock()) return MixResult.Busy
        activeBuildJob = currentCoroutineContext()[Job]
        try {
            refreshTuning()
            refreshSmartFiltersForMix()
            ensureContext(context)
            ensureBllArtistScoresLoaded()
            ensureArtistDecadesLoaded()

            val strictCandidates = filteredGenreCandidateUris(strictGenreArtists[genre])
            val broadCandidates = filteredGenreCandidateUris(genreArtists[genre])
            val baseCandidateUris = when {
                strictCandidates.size >= GENRE_RADIO_MIN_STRICT_CANDIDATES -> strictCandidates
                strictCandidates.isNotEmpty() -> (strictCandidates + broadCandidates)
                    .distinctBy { uri -> MediaIdentity.artistKeyFromUri(uri) ?: uri }
                else -> broadCandidates
            }
            if (baseCandidateUris.isEmpty()) return MixResult.NoMatchingArtists

            val wasPlayingBefore = playerRepository.selectedPlayer.value?.state == PlaybackState.PLAYING
            val baselineTrackUri = playerRepository.queueState.value?.currentItem?.track?.uri

            // Primary path: a finite, coherent seed-track radio for this genre (same
            // engine as Smart Mix, seeded from the user's in-genre tracks). Falls
            // through to the server radio when there are too few in-genre seeds.
            val seedMix = buildGenreRadioSeedMix(genre)
            if (seedMix.size >= SMART_MIX_MIN_TRACKS) {
                playGeneratedMix(queueId, seedMix, genre, "genreRadio[$genre]")
                return MixResult.Played(seedMix.size, genre)
            }
            Log.d(TAG, "Genre radio '$genre': seed-track yielded ${seedMix.size} (<$SMART_MIX_MIN_TRACKS), using server radio")

            val focusDecade = loadGenreRadioFocusDecade(genre, baseCandidateUris)
            val rankedUris = rankGenreRadioArtistUris(baseCandidateUris, focusDecade)
            val libraryUris = prioritizeDecadeCoherentUris(rankedUris, focusDecade)
                .take(MAX_GENRE_RADIO_ARTIST_URIS)
            if (libraryUris.isEmpty()) return MixResult.NoMatchingArtists

            val discoveryUris = resolveDiscoverySeeds(libraryUris, genre)
            val allSeeds = (libraryUris + discoveryUris).distinct()

            Log.d(
                TAG,
                "playGenreRadio: genre='$genre', strict=${strictCandidates.size}, " +
                    "broad=${broadCandidates.size}, decadeFocus=$focusDecade, " +
                    "library=${libraryUris.size}, discovery=${discoveryUris.size}"
            )
            allSeeds.forEachIndexed { i, uri ->
                val key = MediaIdentity.artistKeyFromUri(uri)
                val name = artistByUri[key]?.name ?: artistByUri[uri]?.name ?: uri
                val bll = bllArtistScoreMap[key ?: uri]?.let { String.format("%.2f", it) } ?: "-"
                val decade = artistDominantDecades[key ?: uri]?.toString() ?: "-"
                val tag = if (uri in discoveryUris) " [DISCOVERY]" else ""
                Log.d(TAG, "  seed #${i + 1}: $name (bll=$bll, decade=$decade)$tag $uri")
            }
            val requestAccepted = startGenreRadioWithFallback(queueId, genre, allSeeds)
            if (!requestAccepted) {
                Log.w(TAG, "playGenreRadio: all seed attempts failed for genre='$genre'")
                return MixResult.Failed("Failed to start $genre radio")
            }
            val started = waitForGenreRadioStart(wasPlayingBefore, baselineTrackUri)
            if (!started) {
                Log.w(TAG, "playGenreRadio: start confirmation timeout for genre='$genre'")
                return MixResult.StartTimeout
            }
            sanitizeGenreRadioQueue(queueId, genre)
            logQueueContents(queueId, "genreRadio[$genre]", awaitQueueItems = true)
            return MixResult.Started
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "playGenreRadio failed", e)
            return MixResult.Failed("Failed to start $genre radio")
        } finally {
            activeBuildJob = null
            buildMutex.unlock()
        }
    }

    // ---------------------------------------------------------------------------
    // Context / scoring loaders
    // ---------------------------------------------------------------------------

    private suspend fun refreshTuning() {
        smartMixVariety = settingsRepository.smartMixVariety.first().toDouble()
        smartMixDiscovery = settingsRepository.smartMixDiscovery.first().toDouble()
        smartMixLength = settingsRepository.smartMixLength.first().toDouble()
        smartMixStrictness = settingsRepository.smartMixStrictness.first().toDouble()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ensureContext(context: LibraryContext?) {
        if (context != null) {
            artistByUri = context.artistByUri
            genreArtists = context.genreArtists
            strictGenreArtists = context.strictGenreArtists
            return
        }
        // Car path: no UI-maintained context. Self-load via the same loader the
        // Discover screen uses, reusing a recent load within a short TTL so a Smart
        // Mix immediately followed by a Genre Radio does not reload the library.
        val fresh = artistByUri.isNotEmpty() &&
            selfLoadedExcluded == excludedArtistUris &&
            System.currentTimeMillis() - selfLoadedContextAtMs < CONTEXT_SELF_LOAD_TTL_MS
        if (fresh) return
        val bundle = contentLoader.load(excludedArtistUris)
        artistByUri = bundle.mergedArtists.mapNotNull { artist ->
            artist.canonicalKey()?.let { it to artist }
        }.toMap()
        genreArtists = bundle.genreArtists
        strictGenreArtists = bundle.strictGenreArtists
        selfLoadedContextAtMs = System.currentTimeMillis()
        selfLoadedExcluded = excludedArtistUris
        Log.d(TAG, "Self-loaded mix context: ${artistByUri.size} artists, ${genreArtists.size} genres")
    }

    private suspend fun refreshSmartFiltersForMix() {
        // Blocked artists are an EXPLICIT user action: always excluded, even when
        // Smart Listening (the preference-learning layer) is off.
        val blockedInfos = smartListeningRepository.getBlockedArtists()
        val blocked = blockedInfos.map { it.artistUri }.toSet()
        blockedArtistNames = blockedInfos.mapNotNull { it.artistName?.takeIf(String::isNotBlank) }.toSet()
        val smartListeningEnabled = settingsRepository.smartListeningEnabled.first()
        if (smartListeningEnabled) {
            val suppressed = smartListeningRepository.getSuppressedArtistUris(days = 120)
            val metrics = smartListeningRepository.getArtistMetrics(days = 120)
            excludedArtistUris = blocked + suppressed
            excludedTrackUris = smartListeningRepository.getSuppressedTrackUris()
            smartArtistScoreMap = metrics.mapValues { it.value.score }
        } else {
            excludedArtistUris = blocked
            excludedTrackUris = emptySet()
            smartArtistScoreMap = emptyMap()
        }
    }

    private suspend fun ensureBllArtistScoresLoaded() {
        if (bllArtistScoreMap.isNotEmpty()) return
        bllArtistScoreMap = try {
            playHistoryRepository.getScoredArtists(days = 90, limit = BLL_ARTIST_SCORE_LIMIT)
                .toScoreMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun ensureArtistDecadesLoaded() {
        if (artistDominantDecades.isNotEmpty()) return
        artistDominantDecades = try {
            playHistoryRepository.getArtistDominantDecades(days = ARTIST_DECADE_LOOKBACK_DAYS)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---------------------------------------------------------------------------
    // Shared play path
    // ---------------------------------------------------------------------------

    // Shared play path for a generated finite mix (Smart Mix + seed-track Genre
    // Radio): mark the queue smart-generated, record recent history/artists/genre
    // for the next round's cool-down, then replace the queue. DSTM is left
    // untouched (MA 2.9.1 atomic replace keeps the curated list clean).
    private suspend fun playGeneratedMix(
        queueId: String,
        tracks: List<Track>,
        pickedGenre: String?,
        logSource: String
    ) {
        playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.SMART_GENERATED)
        val freshUris = tracks.mapNotNull { it.uri.takeIf(String::isNotBlank) }.toSet()
        if (freshUris.isNotEmpty()) {
            recentSmartMixHistory.addLast(freshUris)
            while (recentSmartMixHistory.size > SMART_MIX_HISTORY_DEPTH) recentSmartMixHistory.removeFirst()
        }
        val freshArtistKeys = tracks.mapNotNull { track ->
            MediaIdentity.canonicalArtistKey(track.artistItemId, track.artistUri)
                ?: track.artistNames.split(",").firstOrNull()?.trim()?.lowercase()
        }.toSet()
        if (freshArtistKeys.isNotEmpty()) {
            recentSmartMixArtists.addLast(freshArtistKeys)
            while (recentSmartMixArtists.size > RECENT_ARTIST_HISTORY_DEPTH) recentSmartMixArtists.removeFirst()
        }
        pickedGenre?.let { picked ->
            recentSmartMixGenres.addLast(normalizeGenre(picked))
            while (recentSmartMixGenres.size > RECENT_GENRE_EXCLUSION_DEPTH) recentSmartMixGenres.removeFirst()
        }
        musicRepository.playMedia(
            queueId = queueId,
            uris = tracks.map { it.uri },
            option = "replace",
            awaitResponse = true
        )
        logQueueContents(queueId, logSource, awaitQueueItems = true)
    }

    // ---------------------------------------------------------------------------
    // Smart Mix build
    // ---------------------------------------------------------------------------

    private fun smartMixTrackTarget(): Int =
        (SMART_MIX_TARGET_MIN + smartMixLength * SMART_MIX_TARGET_SPAN).toInt()

    private suspend fun buildSeedTrackMix(): SmartMixResult =
        SmartMixResult(
            seedTrackMixGenerator.buildSmartMix(seedTuning(), smartMixTrackTarget(), currentRecency()),
            null
        )

    private suspend fun buildGenreRadioSeedMix(genre: String): List<Track> =
        seedTrackMixGenerator.buildGenreRadio(genre, seedTuning(), smartMixTrackTarget(), currentRecency())

    private fun seedTuning() =
        SeedTrackMixGenerator.Tuning(smartMixVariety, smartMixDiscovery, smartMixStrictness)

    private fun currentRecency() = SeedTrackMixGenerator.Recency(
        excludedTrackUris = excludedTrackUris,
        recentArtistCounts = recentSmartMixArtists.flatten().groupingBy { it }.eachCount(),
        recentMixTrackUris = recentSmartMixHistory.flatten().toSet(),
        blockedArtistNames = blockedArtistNames
    )

    private suspend fun buildSmartMixTracks(): SmartMixResult {
        if (!hasLastFmKey()) {
            Log.d(TAG, "Smart mix: no Last.fm key, using genre engine directly")
            return buildGenreSmartMixTracks()
        }
        val seedResult = try {
            buildSeedTrackMix()
        } catch (e: Exception) {
            Log.w(TAG, "Seed-track mix failed, falling back to genre engine: ${e.message}")
            SmartMixResult(emptyList(), null)
        }
        if (seedResult.tracks.size >= SMART_MIX_MIN_TRACKS) {
            Log.d(TAG, "Seed-track mix produced ${seedResult.tracks.size} tracks")
            return seedResult
        }
        Log.d(TAG, "Seed-track mix yielded ${seedResult.tracks.size} (<$SMART_MIX_MIN_TRACKS), using genre engine")
        return buildGenreSmartMixTracks()
    }

    private suspend fun hasLastFmKey(): Boolean =
        try {
            settingsRepository.lastFmApiKey.first().isNotBlank()
        } catch (_: Exception) {
            false
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun buildGenreSmartMixTracks(): SmartMixResult {
        val mixSeed = System.currentTimeMillis()
        val random = kotlin.random.Random(mixSeed)
        val recentMixTrackUris = recentSmartMixHistory.flatten().toSet()
        val mixExcludedTrackUris = excludedTrackUris + recentMixTrackUris
        val recentArtistAppearances = recentSmartMixArtists.flatten()
            .groupingBy { it }
            .eachCount()
        val recencyPenaltyByKey: Map<String, Double> = recentArtistAppearances
            .mapValues { (_, count) -> count * RECENT_ARTIST_APPEARANCE_PENALTY }
        val rawArtistScores = try {
            playHistoryRepository.getScoredArtists(days = 120, limit = 40)
        } catch (_: Exception) {
            emptyList()
        }
        val artistScores = if (recencyPenaltyByKey.isEmpty()) {
            rawArtistScores
        } else {
            rawArtistScores.map { score ->
                val key = MediaIdentity.artistKeyFromUri(score.artistUri) ?: score.artistUri
                val penalty = recencyPenaltyByKey[key] ?: 0.0
                if (penalty == 0.0) score else score.copy(score = score.score - penalty)
            }
        }
        val bllForMix: Map<String, Double> = if (recencyPenaltyByKey.isEmpty()) {
            bllArtistScoreMap
        } else {
            bllArtistScoreMap.mapValues { (uri, score) ->
                val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                score - (recencyPenaltyByKey[key] ?: 0.0)
            }
        }
        val genreScores = try {
            genreRepository.scoredGenres(days = 120, limit = 10)
        } catch (_: Exception) {
            emptyList()
        }
        val recentArtistScores = try {
            playHistoryRepository.getScoredArtists(days = SMART_MIX_RECENT_LOOKBACK_DAYS, limit = 24)
        } catch (_: Exception) {
            emptyList()
        }
        val recentGenreScores = try {
            genreRepository.scoredGenres(days = SMART_MIX_RECENT_LOOKBACK_DAYS, limit = 8)
        } catch (_: Exception) {
            emptyList()
        }
        if (artistScores.isEmpty() && genreScores.isEmpty()) return SmartMixResult(emptyList(), null)
        val genreAdjacencyMap = try {
            genreRepository.adjacencyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val artistScoreMap = artistScores.toScoreMap()
        val recentArtistScoreMap = recentArtistScores.toScoreMap()
        val daypartAffinity = try {
            playHistoryRepository.getArtistDaypartAffinity(
                targetHour = LocalTime.now().hour,
                days = SMART_MIX_DAYPART_LOOKBACK_DAYS
            )
        } catch (_: Exception) {
            emptyMap()
        }
        val favoriteArtistUris = contentLoader.loadFavoriteArtistKeys(SMART_MIX_FAVORITES_QUERY_LIMIT)
        val favoriteAlbumUris = contentLoader.loadFavoriteAlbumKeys(SMART_MIX_FAVORITES_QUERY_LIMIT)

        val mixGenreArtists = genreArtists.mapValues { (_, uris) ->
            uris.mapNotNull { MediaIdentity.artistKeyFromUri(it) }.distinct()
        }
        val blendedGenreScores = blendGenreScores(
            longTerm = genreScores,
            recent = recentGenreScores
        )

        val genreDaypartBoost = mutableMapOf<String, Double>()
        for ((genre, artistKeys) in mixGenreArtists) {
            val affinities = artistKeys.mapNotNull { daypartAffinity[it] }
            if (affinities.isNotEmpty()) {
                genreDaypartBoost[genre] = affinities.average()
            }
        }

        val timeAwareGenreScores = blendedGenreScores.map { gs ->
            val genre = normalizeGenre(gs.genre)
            val dayBoost = genreDaypartBoost[genre] ?: 0.0
            GenreScore(genre, gs.score + dayBoost * DAYPART_GENRE_BOOST_WEIGHT)
        }.sortedByDescending { it.score }

        val recentlyPickedGenres = recentSmartMixGenres.toSet()
        val genrePool = timeAwareGenreScores
            .filter { normalizeGenre(it.genre) !in recentlyPickedGenres }
            .ifEmpty { timeAwareGenreScores }
        val triedGenres = mutableSetOf<String>()
        for (candidate in genrePool.take(5)) {
            val pickedGenre = if (triedGenres.isEmpty()) {
                weightedRandomGenre(
                    genrePool.filter { it.genre !in triedGenres },
                    random
                ) ?: continue
            } else {
                candidate.genre.takeIf { it !in triedGenres } ?: continue
            }
            triedGenres.add(pickedGenre)

            val adjacentGenres = if (smartMixDiscovery < DISCOVERY_ADJACENT_THRESHOLD) {
                emptySet()
            } else {
                genreAdjacencyMap[pickedGenre]?.map { normalizeGenre(it) }?.toSet() ?: emptySet()
            }
            val result = buildTracksForGenre(
                pickedGenre = pickedGenre,
                artistScores = artistScores,
                artistScoreMap = artistScoreMap,
                recentArtistScoreMap = recentArtistScoreMap,
                daypartAffinity = daypartAffinity,
                mixGenreArtists = mixGenreArtists,
                timeAwareGenreScores = timeAwareGenreScores,
                favoriteArtistUris = favoriteArtistUris,
                favoriteAlbumUris = favoriteAlbumUris,
                mixSeed = mixSeed,
                adjacentGenres = adjacentGenres,
                excludedTrackUrisForMix = mixExcludedTrackUris,
                bllForMix = bllForMix
            )
            if (result.size >= SMART_MIX_MIN_TRACKS) {
                Log.d(TAG, "Smart mix: picked genre '$pickedGenre' -> ${result.size} tracks")
                return SmartMixResult(result, pickedGenre)
            }
            Log.d(TAG, "Smart mix: genre '$pickedGenre' only had ${result.size} tracks, trying next")
        }

        Log.d(TAG, "Smart mix: single-genre fallback exhausted, using multi-genre mix")
        val smartMixMode = MixMode.SmartMix(
            artistScores = artistScores,
            genreScores = blendedGenreScores,
            genreArtists = mixGenreArtists,
            recentArtistScoreMap = recentArtistScoreMap,
            daypartAffinityByArtist = daypartAffinity
        )
        val artistOrder = mixEngine.buildArtistOrder(
            mode = smartMixMode,
            bllArtistScoreMap = bllForMix,
            smartArtistScoreMap = smartArtistScoreMap,
            favoriteArtistUris = favoriteArtistUris,
            excludedArtistUris = excludedArtistUris,
            randomSeed = mixSeed,
            discovery = smartMixDiscovery
        )
        if (artistOrder.isEmpty()) return SmartMixResult(emptyList(), null)

        val acceptedGenres = fallbackAcceptedGenres(blendedGenreScores, genreAdjacencyMap)
        val tracksByArtist = fetchTracksByArtist(artistOrder) { _, rawTracks ->
            rawTracks.filter { track ->
                track.genres.isEmpty() || track.genres.any { normalizeGenre(it) in acceptedGenres }
            }
        }
        if (tracksByArtist.isEmpty()) return SmartMixResult(emptyList(), null)

        return SmartMixResult(
            mixEngine.buildTracks(
                mode = smartMixMode,
                artistOrder = artistOrder,
                tracksByArtist = tracksByArtist,
                excludedArtistUris = excludedArtistUris,
                excludedTrackUris = mixExcludedTrackUris,
                favoriteArtistUris = favoriteArtistUris,
                favoriteAlbumUris = favoriteAlbumUris,
                artistBaseScore = { uri ->
                    val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                    (artistScoreMap[key] ?: bllForMix[key] ?: 0.0) +
                        (recentArtistScoreMap[key] ?: 0.0) * 0.75 +
                        (smartArtistScoreMap[key] ?: 0.0) * 0.5 +
                        daypartTrackBias(daypartAffinity[key])
                },
                target = smartMixTrackTarget(),
                randomSeed = mixSeed + 17L,
                variety = smartMixVariety
            ),
            null
        )
    }

    private fun fallbackAcceptedGenres(
        blendedGenreScores: List<GenreScore>,
        genreAdjacencyMap: Map<String, Set<String>>
    ): Set<String> {
        val top = blendedGenreScores.asSequence()
            .map { normalizeGenre(it.genre) }
            .take(FALLBACK_GENRE_BREADTH)
            .toSet()
        if (smartMixDiscovery < DISCOVERY_ADJACENT_THRESHOLD) return top
        return top + top.flatMap { genreAdjacencyMap[it]?.map(::normalizeGenre).orEmpty() }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
    private suspend fun buildTracksForGenre(
        pickedGenre: String,
        artistScores: List<ArtistScore>,
        artistScoreMap: Map<String, Double>,
        recentArtistScoreMap: Map<String, Double>,
        daypartAffinity: Map<String, Double>,
        mixGenreArtists: Map<String, List<String>>,
        timeAwareGenreScores: List<GenreScore>,
        favoriteArtistUris: Set<String>,
        favoriteAlbumUris: Set<String>,
        mixSeed: Long,
        adjacentGenres: Set<String> = emptySet(),
        excludedTrackUrisForMix: Set<String> = excludedTrackUris,
        bllForMix: Map<String, Double> = bllArtistScoreMap
    ): List<Track> {
        val exactArtists = mixGenreArtists[pickedGenre] ?: emptyList()
        val adjacentArtists = adjacentGenres.flatMap { mixGenreArtists[it] ?: emptyList() }
        val genreArtistKeys = (exactArtists + adjacentArtists).distinct().toSet()
        val filteredGenreArtists = mapOf(pickedGenre to genreArtistKeys.toList())
        val filteredGenreScores = timeAwareGenreScores.filter {
            val g = normalizeGenre(it.genre)
            g == pickedGenre || g in adjacentGenres
        }
        val filteredArtistScores = artistScores.filter { it.artistUri in genreArtistKeys }

        val smartMixMode = MixMode.SmartMix(
            artistScores = filteredArtistScores,
            genreScores = filteredGenreScores,
            genreArtists = filteredGenreArtists,
            recentArtistScoreMap = recentArtistScoreMap,
            daypartAffinityByArtist = daypartAffinity
        )
        val genreFavorites = favoriteArtistUris.filter { it in genreArtistKeys }
        val artistOrder = mixEngine.buildArtistOrder(
            mode = smartMixMode,
            bllArtistScoreMap = bllForMix,
            smartArtistScoreMap = smartArtistScoreMap,
            favoriteArtistUris = genreFavorites.toSet(),
            excludedArtistUris = excludedArtistUris,
            randomSeed = mixSeed,
            discovery = smartMixDiscovery
        )
        if (artistOrder.isEmpty()) return emptyList()

        val genreMatchSet = (adjacentGenres + pickedGenre).map { normalizeGenre(it) }.toSet()
        val exactArtistKeys = exactArtists.toSet()
        val trackFilter = { artistUri: String, rawTracks: List<Track> ->
            val key = MediaIdentity.artistKeyFromUri(artistUri) ?: artistUri
            val isExact = key in exactArtistKeys
            rawTracks.filter { track ->
                if (track.genres.isEmpty()) {
                    isExact
                } else {
                    track.genres.any { normalizeGenre(it) in genreMatchSet }
                }
            }
        }

        val target = smartMixTrackTarget()
        val baseTracksByArtist = fetchTracksByArtist(artistOrder, transform = trackFilter)
        val estimatedYield = baseTracksByArtist.values.sumOf { minOf(it.size, MIX_MAX_TRACKS_PER_ARTIST) }
        val needExpansion = estimatedYield < target || smartMixDiscovery >= DISCOVERY_EXPANSION_THRESHOLD
        Log.d(
            TAG,
            "Genre '$pickedGenre': order=${artistOrder.size}, base=${baseTracksByArtist.size} artists " +
                "(${baseTracksByArtist.values.sumOf { it.size }} tracks), est=$estimatedYield/$target, needExp=$needExpansion"
        )

        val expandedArtistOrder: List<String>
        val tracksByArtist: Map<String, List<Track>>
        if (needExpansion) {
            val expansionArtists = resolveLastFmExpansionArtists(
                seedArtistUris = artistOrder,
                excludedArtistUris = excludedArtistUris,
                allowProviderSearch = false
            )
            val seenKeys = artistOrder
                .mapNotNull { MediaIdentity.artistKeyFromUri(it) ?: it.takeIf { it.isNotBlank() } }
                .toMutableSet()
            val newcomers = expansionArtists.filter { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                seenKeys.add(key)
            }
            if (newcomers.isEmpty()) {
                expandedArtistOrder = artistOrder
                tracksByArtist = baseTracksByArtist
            } else {
                val anchorSlots = 2.coerceAtMost(artistOrder.size)
                expandedArtistOrder =
                    artistOrder.take(anchorSlots) + newcomers + artistOrder.drop(anchorSlots)
                tracksByArtist = baseTracksByArtist +
                    fetchTracksByArtist(newcomers, cacheOnly = true, transform = trackFilter)
            }
            Log.d(
                TAG,
                "Genre '$pickedGenre': expansion resolved=${expansionArtists.size}, new=${newcomers.size}, " +
                    "total=${tracksByArtist.size} artists (${tracksByArtist.values.sumOf { it.size }} tracks)"
            )
        } else {
            expandedArtistOrder = artistOrder
            tracksByArtist = baseTracksByArtist
        }

        if (smartMixDiscovery >= DISCOVERY_EXPANSION_THRESHOLD) {
            scheduleExpansionPrefetch(artistOrder, excludedArtistUris)
        }

        if (tracksByArtist.isEmpty()) return emptyList()

        return mixEngine.buildTracks(
            mode = smartMixMode,
            artistOrder = expandedArtistOrder,
            tracksByArtist = tracksByArtist,
            excludedArtistUris = excludedArtistUris,
            excludedTrackUris = excludedTrackUrisForMix,
            favoriteArtistUris = genreFavorites.toSet(),
            favoriteAlbumUris = favoriteAlbumUris,
            artistBaseScore = { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                (artistScoreMap[key] ?: bllForMix[key] ?: 0.0) +
                    (recentArtistScoreMap[key] ?: 0.0) * 0.75 +
                    (smartArtistScoreMap[key] ?: 0.0) * 0.5 +
                    daypartTrackBias(daypartAffinity[key])
            },
            target = target,
            randomSeed = mixSeed + 17L,
            adjacentGenres = adjacentGenres,
            variety = smartMixVariety
        )
    }

    private fun weightedRandomGenre(scores: List<GenreScore>, random: kotlin.random.Random): String? {
        if (scores.isEmpty()) return null
        val total = scores.sumOf { maxOf(it.score, 0.0) }
        if (total <= 0.0) return normalizeGenre(scores.first().genre)
        var pick = random.nextDouble(total)
        for (s in scores) {
            pick -= maxOf(s.score, 0.0)
            if (pick <= 0.0) return normalizeGenre(s.genre)
        }
        return normalizeGenre(scores.last().genre)
    }

    private fun blendGenreScores(
        longTerm: List<GenreScore>,
        recent: List<GenreScore>
    ): List<GenreScore> {
        if (recent.isEmpty()) return longTerm
        val merged = mutableMapOf<String, Double>()
        for (score in longTerm) {
            merged[normalizeGenre(score.genre)] = (merged[normalizeGenre(score.genre)] ?: 0.0) + score.score
        }
        for (score in recent) {
            val genre = normalizeGenre(score.genre)
            merged[genre] = (merged[genre] ?: 0.0) + score.score * 0.9
        }
        return merged.entries
            .sortedByDescending { it.value }
            .map { GenreScore(it.key, it.value) }
    }

    private fun daypartTrackBias(affinity: Double?): Double {
        if (affinity == null) return 0.0
        return ((affinity - 0.35) * 0.95).coerceIn(-0.25, 0.50)
    }

    // ---------------------------------------------------------------------------
    // Last.fm expansion
    // ---------------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun resolveLastFmExpansionArtists(
        seedArtistUris: List<String>,
        excludedArtistUris: Set<String>,
        targetCount: Int = SMART_MIX_LASTFM_EXPANSION,
        allowProviderSearch: Boolean = false
    ): List<String> {
        if (seedArtistUris.isEmpty() || targetCount <= 0) return emptyList()

        val seedNames = seedArtistUris
            .take(SMART_MIX_LASTFM_SEED_LIMIT)
            .mapNotNull { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                artistByUri[key]?.name ?: artistByUri[uri]?.name
            }
            .filter { it.isNotBlank() }
        if (seedNames.isEmpty()) return emptyList()

        val nameToUri = artistByUri.entries
            .associate { (uri, artist) -> artist.name.lowercase() to uri }
        if (nameToUri.isEmpty()) return emptyList()

        val alreadyInPoolNames = seedArtistUris
            .mapNotNull { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                (artistByUri[key]?.name ?: artistByUri[uri]?.name)?.lowercase()
            }
            .toMutableSet()

        val collected = mutableListOf<String>()
        val newNames = mutableListOf<String>()
        coroutineScope {
            val deferreds = seedNames.map { name ->
                async {
                    try {
                        lastFmSimilarResolver.resolve(name, limit = SMART_MIX_LASTFM_SIMILARS_PER_SEED)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            val merged = deferreds.flatMap { it.await() }
                .sortedByDescending { it.matchScore }

            for (similar in merged) {
                if (similar.name in alreadyInPoolNames) continue
                val libraryUri = nameToUri[similar.name]
                if (libraryUri != null) {
                    if (libraryUri in excludedArtistUris) continue
                    if (collected.size < targetCount) {
                        collected += libraryUri
                        alreadyInPoolNames += similar.name
                    }
                } else {
                    newNames += similar.name
                    alreadyInPoolNames += similar.name
                }
            }
        }

        if (collected.size < targetCount && newNames.isNotEmpty()) {
            val gate = Semaphore(ARTIST_FETCH_CONCURRENCY)
            var searchBudget = if (allowProviderSearch) SMART_MIX_MAX_SEARCHES else 0
            val resolved = coroutineScope {
                newNames.mapNotNull { name ->
                    val cachedUri = playHistoryRepository.getCachedResolvedArtistUri(
                        name, RESOLVED_ARTIST_TTL_MS
                    )
                    when {
                        cachedUri != null -> async { cachedUri }
                        searchBudget > 0 -> {
                            searchBudget--
                            async { gate.withPermit { searchAndCacheArtistUri(name) } }
                        }
                        else -> null
                    }
                }.awaitAll()
            }
            for (uri in resolved) {
                if (collected.size >= targetCount) break
                if (uri.isNullOrBlank() || uri in excludedArtistUris) continue
                val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                if (collected.any { (MediaIdentity.artistKeyFromUri(it) ?: it) == key }) continue
                collected += uri
            }
        }

        if (collected.isNotEmpty()) {
            Log.d(
                TAG,
                "Last.fm expansion: ${collected.size}/$targetCount artists from ${seedNames.size} seeds " +
                    "(${newNames.size} new candidates searched)"
            )
        }
        return collected
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpansionPrefetch(
        seedArtistUris: List<String>,
        excludedArtistUris: Set<String>
    ) {
        if (expansionPrefetchJob?.isActive == true) return
        expansionPrefetchJob = scope.launch {
            try {
                val artists = resolveLastFmExpansionArtists(
                    seedArtistUris = seedArtistUris,
                    excludedArtistUris = excludedArtistUris,
                    allowProviderSearch = true
                )
                if (artists.isNotEmpty()) {
                    fetchTracksByArtist(artists, concurrency = PREFETCH_CONCURRENCY) { _, tracks -> tracks }
                    Log.d(TAG, "Expansion prefetch: warmed ${artists.size} artists")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Expansion prefetch failed: ${e.message}")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun searchAndCacheArtistUri(name: String): String? {
        return try {
            val result = withTimeoutOrNull(SMART_MIX_SEARCH_TIMEOUT_MS) {
                musicRepository.search(
                    query = name,
                    mediaTypes = listOf(MediaType.ARTIST),
                    limit = SMART_MIX_SEARCH_LIMIT
                )
            } ?: return null
            val match = result.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val uri = match?.uri?.takeIf { it.isNotBlank() }
            if (uri != null) playHistoryRepository.cacheResolvedArtistUri(name, uri)
            uri
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveDiscoverySeeds(libraryUris: List<String>, genre: String): List<String> {
        val seedArtists = libraryUris.take(GENRE_RADIO_SIMILAR_RESOLVE_LIMIT).mapNotNull { uri ->
            val key = MediaIdentity.artistKeyFromUri(uri)
            artistByUri[key]?.name ?: artistByUri[uri]?.name
        }
        if (seedArtists.isEmpty()) return emptyList()

        val libraryNames = artistByUri.values.map { it.name.lowercase() }.toSet()
        val discoveryNames = mutableSetOf<String>()

        coroutineScope {
            val deferreds = seedArtists.map { name ->
                async {
                    try {
                        lastFmSimilarResolver.resolve(name)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            for (deferred in deferreds) {
                for (similar in deferred.await()) {
                    if (similar.name !in libraryNames && similar.matchScore >= 0.15) {
                        discoveryNames.add(similar.name)
                    }
                }
            }
        }
        if (discoveryNames.isEmpty()) return emptyList()

        val normalizedTarget = normalizeGenre(genre)
        val validatedNames = mutableListOf<String>()
        coroutineScope {
            val genreDeferreds = discoveryNames.map { name ->
                async {
                    try {
                        val genres = lastFmGenreResolver.resolve(name)
                        if (genres.any { normalizeGenre(it) == normalizedTarget }) name else null
                    } catch (_: Exception) { null }
                }
            }
            for (deferred in genreDeferreds) {
                deferred.await()?.let { validatedNames.add(it) }
            }
        }
        Log.d(
            TAG,
            "resolveDiscoverySeeds: genre-validated ${validatedNames.size}/${discoveryNames.size} for '$genre'"
        )
        if (validatedNames.isEmpty()) return emptyList()

        val discoveryUris = mutableListOf<String>()
        for (name in validatedNames.take(GENRE_RADIO_DISCOVERY_SEEDS * 2)) {
            if (discoveryUris.size >= GENRE_RADIO_DISCOVERY_SEEDS) break
            try {
                val result = musicRepository.search(name, mediaTypes = listOf(MediaType.ARTIST), limit = 1)
                val artist = result.artists.firstOrNull() ?: continue
                if (artist.name.lowercase() == name) {
                    discoveryUris.add(artist.uri)
                    Log.d(TAG, "Discovery seed: $name -> ${artist.uri}")
                }
            } catch (_: Exception) {
                // skip
            }
        }
        Log.d(TAG, "resolveDiscoverySeeds: ${discoveryUris.size} found from ${validatedNames.size} validated")
        return discoveryUris
    }

    // ---------------------------------------------------------------------------
    // Genre Radio server-radio fallback
    // ---------------------------------------------------------------------------

    private fun filteredGenreCandidateUris(uris: List<String>?): List<String> {
        return uris
            .orEmpty()
            .distinctBy { uri -> MediaIdentity.artistKeyFromUri(uri) ?: uri }
            .filterNot { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri)
                key != null && key in excludedArtistUris
            }
    }

    private suspend fun startGenreRadioWithFallback(
        queueId: String,
        genre: String,
        rankedUris: List<String>
    ): Boolean {
        val seedPool = rankedUris.take(GENRE_RADIO_ATTEMPT_POOL_SIZE)
        if (seedPool.isEmpty()) return false

        for (batchSize in GENRE_RADIO_BATCH_SIZES) {
            val batches = seedPool.chunked(batchSize)
            for ((index, batch) in batches.withIndex()) {
                try {
                    Log.d(
                        TAG,
                        "startGenreRadio attempt: genre='$genre', batchSize=${batch.size}, " +
                            "batch=${index + 1}/${batches.size}, seeds=${batch.joinToString()}"
                    )
                    playerRepository.setQueueFilterMode(
                        queueId,
                        PlayerRepository.QueueFilterMode.RADIO_SMART
                    )
                    musicRepository.playMedia(
                        queueId = queueId,
                        uris = batch,
                        radioMode = true,
                        awaitResponse = true,
                        timeoutMs = 90_000
                    )
                    return true
                } catch (e: MaApiException) {
                    if (!isRetryableGenreRadioMetadataError(e)) throw e
                    Log.w(
                        TAG,
                        "startGenreRadio attempt failed: genre='$genre', batchSize=${batch.size}, " +
                            "seeds=${batch.joinToString()} error=${e.message}"
                    )
                }
            }
        }
        return false
    }

    private fun isRetryableGenreRadioMetadataError(error: MaApiException): Boolean {
        val msg = error.message?.lowercase().orEmpty()
        return error.code == GENRE_RADIO_METADATA_ERROR_CODE && "year 0 is out of range" in msg
    }

    private suspend fun loadGenreRadioFocusDecade(
        genre: String,
        candidateUris: List<String>
    ): Int? {
        val topForGenre = try {
            genreRepository.decadesForGenre(
                genre = genre,
                days = GENRE_RADIO_DECADE_LOOKBACK_DAYS,
                limit = 2
            )
        } catch (_: Exception) {
            emptyList()
        }
        if (topForGenre.isNotEmpty()) return topForGenre.first().decade

        val weighted = candidateUris.mapNotNull { uri ->
            val key = MediaIdentity.artistKeyFromUri(uri) ?: return@mapNotNull null
            val decade = artistDominantDecades[key] ?: return@mapNotNull null
            val weight = ((bllArtistScoreMap[key] ?: 0.0) + (smartArtistScoreMap[key] ?: 0.0) * 0.5 + 1.0)
                .coerceAtLeast(0.1)
            decade to weight
        }
        if (weighted.isEmpty()) return null
        return weighted
            .groupBy({ it.first }, { it.second })
            .maxByOrNull { (_, weights) -> weights.sum() }
            ?.key
    }

    private fun rankGenreRadioArtistUris(candidateUris: List<String>, focusDecade: Int?): List<String> {
        val unique = candidateUris.distinctBy { uri -> MediaIdentity.artistKeyFromUri(uri) ?: uri }
        if (unique.isEmpty()) return emptyList()
        val ranked = unique.shuffled().sortedByDescending { uri ->
            val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
            val bll = bllArtistScoreMap[key] ?: 0.0
            val smart = smartArtistScoreMap[key] ?: 0.0
            val base = bll + (smart * 0.5)
            base + decadeAdjustment(uri, focusDecade)
        }
        val explorationCount = minOf(GENRE_RADIO_EXPLORATION_COUNT, ranked.size / 4)
        val exploitCount = (MAX_GENRE_RADIO_ARTIST_URIS - explorationCount).coerceAtLeast(1)
        val exploit = ranked.take(exploitCount)
        val tail = ranked.drop(exploitCount)
        val explorePool = if (focusDecade == null) {
            tail
        } else {
            tail.filterNot { isFarDecade(it, focusDecade) }
        }
        val explore = (if (explorePool.isNotEmpty()) explorePool else tail)
            .shuffled()
            .take(explorationCount)
        val orderedTail = tail.filterNot { it in explore }
        return (exploit + explore + orderedTail).distinctBy { uri -> MediaIdentity.artistKeyFromUri(uri) ?: uri }
    }

    private fun prioritizeDecadeCoherentUris(rankedUris: List<String>, focusDecade: Int?): List<String> {
        if (focusDecade == null) return rankedUris
        val preferred = rankedUris.filter { uri ->
            val key = MediaIdentity.artistKeyFromUri(uri) ?: return@filter true
            val decade = artistDominantDecades[key] ?: return@filter true
            abs(decade - focusDecade) <= GENRE_RADIO_ALLOWED_DECADE_GAP
        }
        val fallback = rankedUris.filterNot { it in preferred }
        return preferred + fallback
    }

    private fun decadeAdjustment(uri: String, focusDecade: Int?): Double {
        if (focusDecade == null) return 0.0
        val key = MediaIdentity.artistKeyFromUri(uri) ?: return 0.0
        val decade = artistDominantDecades[key] ?: return 0.0
        val gap = abs(decade - focusDecade)
        return when {
            gap == 0 -> 1.0
            gap <= GENRE_RADIO_ALLOWED_DECADE_GAP -> 0.35
            gap >= 20 -> -1.0
            else -> -0.25
        }
    }

    private fun isFarDecade(uri: String, focusDecade: Int): Boolean {
        val key = MediaIdentity.artistKeyFromUri(uri) ?: return false
        val decade = artistDominantDecades[key] ?: return false
        return abs(decade - focusDecade) > GENRE_RADIO_ALLOWED_DECADE_GAP
    }

    private suspend fun waitForGenreRadioStart(
        wasPlayingBefore: Boolean,
        baselineTrackUri: String?
    ): Boolean {
        val started = withTimeoutOrNull(GENRE_RADIO_START_WAIT_TIMEOUT_MS) {
            while (true) {
                val player = playerRepository.selectedPlayer.value
                val isPlayingNow = player?.state == PlaybackState.PLAYING
                val currentTrackUri = playerRepository.queueState.value?.currentItem?.track?.uri
                    ?: player?.currentMedia?.uri
                val playbackStarted = !wasPlayingBefore && isPlayingNow
                val trackChanged = !baselineTrackUri.isNullOrBlank() &&
                    !currentTrackUri.isNullOrBlank() &&
                    currentTrackUri != baselineTrackUri
                val gainedTrackContext = wasPlayingBefore &&
                    baselineTrackUri.isNullOrBlank() &&
                    !currentTrackUri.isNullOrBlank()

                if (playbackStarted || trackChanged || gainedTrackContext) {
                    return@withTimeoutOrNull true
                }
                delay(120)
            }
        }
        return started == true
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun sanitizeGenreRadioQueue(queueId: String, genre: String) {
        try {
            val items = musicRepository.getQueueItems(queueId)
            if (items.size < 2) return

            val artistGenreMap = resolveQueueArtistGenres(items)
            val seenTrackUris = mutableSetOf<String>()
            var previousArtistKeys = emptySet<String>()
            val removeQueueItemIds = mutableListOf<String>()
            var offGenreCount = 0

            items.forEachIndexed { index, item ->
                val track = item.track
                val trackUri = track?.uri.orEmpty()
                val artistKeys = buildSet {
                    track?.artistUris?.forEach(::add)
                    track?.artistUri?.let { uri ->
                        MediaIdentity.canonicalArtistKey(uri = uri)?.let(::add)
                    }
                }

                val duplicateTrack = trackUri.isNotBlank() && !seenTrackUris.add(trackUri)
                val consecutiveSameArtist = index > 0 &&
                    artistKeys.isNotEmpty() &&
                    previousArtistKeys.isNotEmpty() &&
                    artistKeys.any { it in previousArtistKeys }
                val blockedArtist = artistKeys.any { it in excludedArtistUris }

                val artistName = track?.artistNames?.split(",")?.firstOrNull()?.trim()
                val artistGenres = artistName?.let { artistGenreMap[it.lowercase()] }
                val offGenre = !artistGenres.isNullOrEmpty() &&
                    artistGenres.none { isGenreRelated(it, genre) }

                if (duplicateTrack || consecutiveSameArtist || offGenre || blockedArtist) {
                    removeQueueItemIds += item.queueItemId
                    if (offGenre) {
                        offGenreCount++
                        Log.d(TAG, "sanitize off-genre: ${track?.artistNames} - ${track?.name} genres=$artistGenres")
                    }
                } else if (artistKeys.isNotEmpty()) {
                    previousArtistKeys = artistKeys
                }
            }

            if (removeQueueItemIds.isEmpty()) return

            removeQueueItemIds.forEach { itemId ->
                musicRepository.deleteQueueItem(queueId, itemId)
            }
            Log.d(
                TAG,
                "sanitizeGenreRadioQueue[$genre]: removed ${removeQueueItemIds.size} items ($offGenreCount off-genre)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "sanitizeGenreRadioQueue failed: ${e.message}")
        }
    }

    private suspend fun resolveQueueArtistGenres(
        items: List<QueueItem>
    ): Map<String, List<String>> {
        val artistNames = items.mapNotNull { item ->
            item.track?.artistNames?.split(",")?.firstOrNull()?.trim()
        }.distinct()
        if (artistNames.isEmpty()) return emptyMap()

        val trackGenresByArtist = mutableMapOf<String, List<String>>()
        for (item in items) {
            val track = item.track ?: continue
            val name = track.artistNames.split(",").firstOrNull()?.trim() ?: continue
            val genres = track.genres
            if (genres.isNotEmpty() && name.lowercase() !in trackGenresByArtist) {
                trackGenresByArtist[name.lowercase()] = genres
            }
        }

        val needsLookup = artistNames.filter { it.lowercase() !in trackGenresByArtist }
        val resolved = if (needsLookup.isNotEmpty()) {
            coroutineScope {
                needsLookup.map { name ->
                    async {
                        name.lowercase() to try {
                            lastFmGenreResolver.resolve(name)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.associate { it.await() }
            }
        } else {
            emptyMap()
        }

        val allResolved = trackGenresByArtist + resolved
        for ((nameLower, genres) in allResolved) {
            if (genres.isNotEmpty()) {
                try {
                    val originalName = artistNames.firstOrNull { it.lowercase() == nameLower }
                        ?: continue
                    playHistoryRepository.enrichArtistGenres(originalName, genres)
                } catch (_: Exception) { /* best-effort */ }
            }
        }
        return allResolved
    }

    private fun isGenreRelated(artistGenre: String, targetGenre: String): Boolean {
        if (artistGenre == targetGenre) return true
        if (artistGenre.contains(targetGenre) || targetGenre.contains(artistGenre)) return true
        val normArtist = normalizeGenre(artistGenre)
        val normTarget = normalizeGenre(targetGenre)
        val artistFamilies = GENRE_FAMILIES.filter { normArtist in it }
        val targetFamilies = GENRE_FAMILIES.filter { normTarget in it }
        return artistFamilies.any { it in targetFamilies }
    }

    // ---------------------------------------------------------------------------
    // Track fetching
    // ---------------------------------------------------------------------------

    private suspend fun fetchTracksByArtist(
        artistOrder: List<String>,
        cacheOnly: Boolean = false,
        concurrency: Int = ARTIST_FETCH_CONCURRENCY,
        transform: (String, List<Track>) -> List<Track>
    ): Map<String, List<Track>> = coroutineScope {
        val gate = Semaphore(concurrency)
        artistOrder
            .map { artistUri ->
                async {
                    artistUri to gate.withPermit {
                        transform(artistUri, getArtistTracksForIdentity(artistUri, cacheOnly))
                    }
                }
            }
            .awaitAll()
            .filter { it.second.isNotEmpty() }
            .toMap()
    }

    private fun sampleTracksForMix(tracks: List<Track>, seed: Long): List<Track> =
        if (tracks.size <= MAX_TRACKS_PER_ARTIST) {
            tracks
        } else {
            tracks.shuffled(kotlin.random.Random(seed)).take(MAX_TRACKS_PER_ARTIST)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun getArtistTracksForIdentity(
        artistIdentity: String,
        cacheOnly: Boolean = false
    ): List<Track> {
        val cacheKey = MediaIdentity.artistKeyFromUri(artistIdentity) ?: artistIdentity
        playHistoryRepository.getCachedArtistTracks(cacheKey, ARTIST_TRACK_CACHE_TTL_MS)?.let { cached ->
            if (cached.isNotEmpty()) {
                val capped = sampleTracksForMix(cached, cacheKey.hashCode().toLong())
                Log.d(TAG, "Artist track cache hit: $cacheKey (${capped.size}/${cached.size} tracks)")
                return enrichTracksWithLastFmGenres(capped)
            }
        }
        if (cacheOnly) return emptyList()
        for ((provider, itemId) in resolveArtistRefs(artistIdentity)) {
            try {
                val tracks = musicRepository.getArtistTracks(itemId, provider)
                if (tracks.isNotEmpty()) {
                    val capped = sampleTracksForMix(tracks, cacheKey.hashCode().toLong())
                    val enriched = enrichTracksWithLastFmGenres(capped)
                    playHistoryRepository.cacheArtistTracks(cacheKey, enriched)
                    Log.d(TAG, "Artist track cache fill: $cacheKey (${enriched.size}/${tracks.size} tracks)")
                    return enriched
                }
            } catch (_: Exception) {
                // Try next candidate ref.
            }
        }
        return emptyList()
    }

    private suspend fun enrichTracksWithLastFmGenres(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks
        if (tracks.all { it.genres.isNotEmpty() }) return tracks
        val artistName = tracks.first().artistNames.split(",").firstOrNull()?.trim()
        if (artistName.isNullOrBlank()) return tracks
        val genres = try {
            lastFmGenreResolver.resolve(artistName)
        } catch (_: Exception) {
            emptyList()
        }
        if (genres.isEmpty()) return tracks
        return tracks.map { if (it.genres.isEmpty()) it.copy(genres = genres) else it }
    }

    private fun resolveArtistRefs(artistIdentity: String): List<Pair<String, String>> {
        val refs = linkedSetOf<Pair<String, String>>()
        val canonical = MediaIdentity.artistKeyFromUri(artistIdentity) ?: artistIdentity

        val cached = artistByUri[canonical] ?: artistByUri[artistIdentity]
        if (cached != null && cached.provider.isNotBlank() && cached.itemId.isNotBlank()) {
            refs += cached.provider to cached.itemId
        }

        parseMediaUri(artistIdentity)?.let { refs += it }

        if (canonical.isNotBlank()) {
            refs += "library" to canonical
        }

        return refs.toList()
    }

    private fun parseMediaUri(uri: String): Pair<String, String>? {
        val sep = uri.indexOf("://")
        if (sep < 0) return null
        val provider = uri.substring(0, sep)
        val itemId = uri.substringAfterLast("/")
        return if (provider.isNotBlank() && itemId.isNotBlank()) provider to itemId else null
    }

    // ---------------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private suspend fun logQueueContents(queueId: String, source: String, awaitQueueItems: Boolean = false) {
        try {
            val baselineTrackUri = playerRepository.queueState.value?.currentItem?.track?.uri
                ?: playerRepository.selectedPlayer.value?.currentMedia?.uri
            val items = if (awaitQueueItems) {
                awaitQueueItems(queueId, baselineTrackUri)
            } else {
                musicRepository.getQueueItems(queueId)
            }
            Log.d(TAG, "Queue [$source]: ${items.size} tracks")
            items.forEachIndexed { i, item ->
                val artist = item.track?.artistNames ?: ""
                val name = item.track?.name ?: item.name
                Log.d(TAG, "  #${i + 1}: $artist - $name")
            }
        } catch (e: Exception) {
            Log.w(TAG, "logQueueContents failed: ${e.message}")
        }
    }

    private suspend fun awaitQueueItems(
        queueId: String,
        baselineTrackUri: String?
    ): List<QueueItem> {
        val immediate = musicRepository.getQueueItems(queueId)
        if (immediate.isNotEmpty()) return immediate

        withTimeoutOrNull(4_500L) {
            while (true) {
                val items = musicRepository.getQueueItems(queueId)
                if (items.isNotEmpty()) return@withTimeoutOrNull items

                val currentTrackUri = playerRepository.queueState.value?.currentItem?.track?.uri
                    ?: playerRepository.selectedPlayer.value?.currentMedia?.uri
                if (!currentTrackUri.isNullOrBlank() && currentTrackUri != baselineTrackUri) {
                    val refreshed = musicRepository.getQueueItems(queueId)
                    if (refreshed.isNotEmpty()) return@withTimeoutOrNull refreshed
                }

                withTimeoutOrNull(350L) {
                    playerRepository.queueItemsChanged
                        .filter { it == queueId }
                        .first()
                }
            }
        }

        return musicRepository.getQueueItems(queueId)
    }

    companion object {
        private val GENRE_FAMILIES = listOf(
            setOf("folk", "indie folk", "folk rock", "americana", "acoustic",
                "singer songwriter", "country", "alt country", "neofolk"),
            setOf("indie", "indie rock", "indie pop", "alternative",
                "alternative rock", "dream pop", "shoegaze", "lo fi",
                "britpop", "post rock"),
            setOf("rock", "alternative rock", "classic rock", "hard rock",
                "garage rock", "grunge", "psychedelic rock", "progressive rock",
                "stoner rock", "southern rock", "blues rock", "glam rock",
                "noise rock", "space rock", "art rock", "soft rock", "rockabilly"),
            setOf("pop", "pop rock", "electropop", "synthpop", "synth pop",
                "indie pop", "new wave"),
            setOf("house", "deep house", "tech house", "techno", "minimal",
                "trance", "progressive trance", "dance", "drum and bass",
                "dubstep", "breakbeat", "club", "disco", "electro", "psytrance"),
            setOf("electronic", "electronica", "electropop", "synthpop",
                "synth pop", "house", "techno", "dance"),
            setOf("ambient", "downtempo", "chillout", "trip hop", "lounge",
                "new age", "idm", "electronica", "dark ambient", "drone"),
            setOf("darkwave", "dark electro", "ebm", "goth", "gothic rock",
                "gothic metal", "industrial", "industrial rock",
                "industrial metal", "post punk", "new wave"),
            setOf("hip hop", "rap", "rnb", "rhythm and blues", "soul", "funk",
                "underground hip hop"),
            setOf("metal", "heavy metal", "death metal", "black metal",
                "doom metal", "thrash metal", "progressive metal", "metalcore",
                "post metal", "sludge", "symphonic metal", "power metal",
                "speed metal", "industrial metal", "alternative metal",
                "nu metal", "folk metal", "gothic metal", "viking metal",
                "melodic death metal", "melodic metal", "deathcore",
                "grindcore", "technical death metal", "brutal death metal",
                "atmospheric black metal", "depressive black metal"),
            setOf("punk", "punk rock", "hardcore", "hardcore punk",
                "post hardcore", "pop punk", "screamo", "emo",
                "melodic hardcore"),
            setOf("jazz", "blues", "smooth jazz", "acid jazz", "jazz fusion",
                "fusion", "swing", "nu jazz", "soul", "funk"),
            setOf("classical", "soundtrack", "instrumental",
                "contemporary classical", "neoclassical", "baroque"),
            setOf("reggae", "ska", "dub"),
            setOf("world", "celtic", "latin", "mpb")
        )
    }
}
