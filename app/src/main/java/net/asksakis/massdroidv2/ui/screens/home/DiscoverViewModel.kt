package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ItemByUriArgs
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.GenreItem
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSectionBuilder
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.MixEngine
import net.asksakis.massdroidv2.domain.recommendation.canonicalKey
import net.asksakis.massdroidv2.domain.recommendation.toScoreMap
import net.asksakis.massdroidv2.domain.recommendation.MixMode
import net.asksakis.massdroidv2.domain.recommendation.RecommendationEngine
import net.asksakis.massdroidv2.domain.recommendation.SeedTrackMixGenerator
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import net.asksakis.massdroidv2.domain.shortcut.ShortcutAction
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher
import javax.inject.Inject
import java.time.LocalTime
import kotlin.math.abs

private const val TAG = "DiscoverVM"
private const val MIN_SECTION_ITEMS = 3
private const val RECENT_FAVORITE_ALBUMS_TITLE = "Recent Favorite Albums"
private const val RECENT_FAVORITE_TRACKS_TITLE = "Recent Favorite Tracks"
private const val RECENT_TRACKS_QUERY_FACTOR = 5
private const val BLL_ARTIST_SCORE_LIMIT = 500
private const val MAX_GENRE_RADIO_ARTIST_URIS = 30
private const val GENRE_RADIO_ATTEMPT_POOL_SIZE = 20
private val GENRE_RADIO_BATCH_SIZES = listOf(12, 8, 4, 2, 1)
private const val GENRE_RADIO_EXPLORATION_COUNT = 4
private const val GENRE_RADIO_ALLOWED_DECADE_GAP = 10
private const val GENRE_RADIO_DECADE_LOOKBACK_DAYS = 720
private const val ARTIST_DECADE_LOOKBACK_DAYS = 720
private const val SMART_MIX_DAYPART_LOOKBACK_DAYS = 180
private const val SMART_MIX_RECENT_LOOKBACK_DAYS = 14
// Smart Mix track-count target is derived from the user "length" knob (0..1):
// 20 tracks at 0, 60 at 1, so the neutral 0.5 default lands on a round 40.
private const val SMART_MIX_TARGET_MIN = 20
private const val SMART_MIX_TARGET_SPAN = 40
// Discovery >= this leaves the comfort band: adjacent genres are allowed into
// the mix (below it the mix stays strictly on the picked / top genres).
private const val DISCOVERY_ADJACENT_THRESHOLD = 0.34
// Top genres kept as the accepted-genre anchor for the multi-genre fallback.
private const val FALLBACK_GENRE_BREADTH = 6
// Discovery >= this pays for Last.fm similar-artist expansion even when the local
// pool already fills the target (the user explicitly wants more new artists).
private const val DISCOVERY_EXPANSION_THRESHOLD = 0.66
// Mirrors MixEngine's per-artist track cap: used to estimate how many tracks the
// fetched artist pool can actually yield (each artist gives at most this many).
private const val MIX_MAX_TRACKS_PER_ARTIST = 2
private const val DAYPART_GENRE_BOOST_WEIGHT = 2.0
private const val SMART_MIX_MIN_TRACKS = 8
// How many past mixes contribute to the soft-exclusion window. 3 gives
// back-to-back variety without locking out tracks for an unreasonable amount
// of time once the user does want to hear something again.
private const val SMART_MIX_HISTORY_DEPTH = 3
// How many additional artists to inject into the SmartMix pool by following
// Last.fm "similar artist" edges from the current pool's top scorers. The
// track-level genre filter and per-mix interleaver still gate output, so this
// is a candidate budget rather than a guaranteed track count. Bigger pulls
// surface more underused library artists per mix.
private const val SMART_MIX_LASTFM_EXPANSION = 12
private const val SMART_MIX_LASTFM_SEED_LIMIT = 5
// How many similars to ask Last.fm for per seed. Wider than the expansion
// budget so we can still hit the budget after dedup against the in-pool set.
private const val SMART_MIX_LASTFM_SIMILARS_PER_SEED = 20
// Provider search for resolving genuinely-new (not-owned) Last.fm similar artists.
// MA search is slow (multi-provider), so cap how many cache-MISS searches a single
// mix may run; cache hits are unlimited and free. Resolutions are cached for
// RESOLVED_ARTIST_TTL_MS so subsequent mixes skip the search.
private const val SMART_MIX_SEARCH_LIMIT = 5
private const val SMART_MIX_MAX_SEARCHES = 8
private const val SMART_MIX_SEARCH_TIMEOUT_MS = 4000L
private const val RESOLVED_ARTIST_TTL_MS = 30L * 24 * 60 * 60 * 1000
// Subtracted from an artist's composite score for each of the last few mixes
// they appeared in. The penalty is intentionally harsh because BLL scores for
// heavy-listener favourites can sit well above 1.5; -0.6 per mix barely
// budged them. -1.0 per mix combined with a 4-mix window means an artist who
// surfaced in three consecutive mixes has -3.0 against them, which is enough
// to drop most favourites out of the next ordering.
private const val RECENT_ARTIST_APPEARANCE_PENALTY = 1.0
private const val RECENT_ARTIST_HISTORY_DEPTH = 4
// How many of the most-recent picked genres are blocked from the next
// selection. With heavy listeners, two genres dominate the BLL distribution;
// without an exclusion window the weighted random pick keeps re-electing
// them. Three is enough to force the picker into rarer genres while still
// allowing favourites to return after a short pause.
private const val RECENT_GENRE_EXCLUSION_DEPTH = 3
private const val SMART_MIX_FAVORITES_QUERY_LIMIT = 500
private const val ARTIST_TRACK_CACHE_TTL_MS = 12 * 60 * 60 * 1000L
// Cap tracks kept per artist before the (Last.fm) genre enrichment. The MA
// artist_tracks endpoint ignores a limit arg and returns the full catalogue
// (e.g. 437 for a prolific Deezer artist), and enriching that whole list is what
// made one slow artist stall the Smart Mix build ~22s. The mix only ever picks a
// few tracks per artist (interleaved), so a generous cap keeps the variety the
// scorer/genre-filter needs while bounding the per-artist work.
private const val MAX_TRACKS_PER_ARTIST = 40
// Per-artist track fetches run bounded-parallel instead of strictly sequential:
// most artists resolve instantly from the local cache, but the occasional
// provider artist (Deezer) can be slow/throttled, and serially that one stalls
// the whole mix build. Bounded so total time ~= the slowest single fetch without
// firing a flood of concurrent provider RPCs.
private const val ARTIST_FETCH_CONCURRENCY = 6
// Lower concurrency for the background expansion prefetch so it stays gentle on
// the MA server while the user isn't waiting on it.
private const val PREFETCH_CONCURRENCY = 2
private const val GENRE_RADIO_DISCOVERY_SEEDS = 10
private const val GENRE_RADIO_SIMILAR_RESOLVE_LIMIT = 5
private const val GENRE_RADIO_SPAM_WINDOW_MS = 1_500L
private const val GENRE_RADIO_START_WAIT_TIMEOUT_MS = 8_000L
private const val CONNECTION_PING_SAMPLES = 3
private const val CONNECTION_PING_TIMEOUT_MS = 3_000L
private const val CONNECTION_PING_INTERVAL_MS = 1_200L
private const val CONNECTION_PING_HISTORY_LIMIT = 24
private const val LOAD_COOLDOWN_MS = 30_000L

data class ConnectionProbeState(
    val inProgress: Boolean = false,
    val samplesMs: List<Long> = emptyList(),
    val historyMs: List<Long> = emptyList(),
    val failedSamples: Int = 0,
    val probeMethod: String? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0L
)

private data class SmartMixResult(val tracks: List<Track>, val genre: String?)

data class DiscoverUiState(
    val sections: List<DiscoverSection> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val radioOverlayGenre: String? = null,
    val isBuildingSmartMix: Boolean = false,
    val smartMixMessage: String? = null,
    val connectionProbe: ConnectionProbeState = ConnectionProbeState()
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val playHistoryRepository: PlayHistoryRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val wsClient: MaWebSocketClient,
    private val discoverCache: DiscoverCache,
    private val recommendationEngine: RecommendationEngine,
    private val mixEngine: MixEngine,
    private val seedTrackMixGenerator: SeedTrackMixGenerator,
    private val sectionBuilder: DiscoverSectionBuilder,
    private val lastFmSimilarResolver: net.asksakis.massdroidv2.data.lastfm.LastFmSimilarResolver,
    private val lastFmLibraryEnricher: net.asksakis.massdroidv2.data.lastfm.LastFmLibraryEnricher,
    private val lastFmGenreResolver: net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver,
    private val shortcutDispatcher: ShortcutActionDispatcher,
    private val appUpdateChecker: net.asksakis.massdroidv2.data.update.AppUpdateChecker,
    private val genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository,
    private val sessionEventBus: SessionEventBus,
    private val providerHealthReporter: net.asksakis.massdroidv2.data.util.ProviderHealthReporter
) : ViewModel() {

    private val sectionCoordinator = DiscoverSectionCoordinator(
        recentFavoriteAlbumsTitle = RECENT_FAVORITE_ALBUMS_TITLE,
        recentFavoriteTracksTitle = RECENT_FAVORITE_TRACKS_TITLE
    )
    private val contentLoader = DiscoverContentLoader(musicRepository, genreRepository)
    private val recommendationOrchestrator = DiscoverRecommendationOrchestrator(
        musicRepository = musicRepository,
        playHistoryRepository = playHistoryRepository,
        genreRepository = genreRepository,
        recommendationEngine = recommendationEngine,
        lastFmSimilarResolver = lastFmSimilarResolver,
        lastFmGenreResolver = lastFmGenreResolver,
        providerHealthReporter = providerHealthReporter
    )
    private val _uiState = MutableStateFlow(DiscoverUiState())
    val sections: StateFlow<List<DiscoverSection>> = _uiState.map { it.sections }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val isLoading: StateFlow<Boolean> = _uiState.map { it.isLoading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val isRefreshing: StateFlow<Boolean> = _uiState.map { it.isRefreshing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val radioOverlayGenre: StateFlow<String?> = _uiState.map { it.radioOverlayGenre }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val isBuildingSmartMix: StateFlow<Boolean> = _uiState.map { it.isBuildingSmartMix }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val smartMixMessage: StateFlow<String?> = _uiState.map { it.smartMixMessage }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState
    val connectionProbe: StateFlow<ConnectionProbeState> = _uiState.map { it.connectionProbe }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionProbeState())

    private var genreArtists = mapOf<String, List<String>>()
    private var strictGenreArtists = mapOf<String, List<String>>()
    // Last-known library artists, persisted alongside the rendered sections so the
    // WS-event refreshers can re-save the cache without re-deriving them.
    private var cachedTopArtists = emptyList<Artist>()
    private var cacheStale = true
    private var mediaEventJob: Job? = null
    private var fullLoadJob: Job? = null
    private var lastSuccessfulLoadAt = 0L
    private var radioStartJob: Job? = null
    private var connectionProbeJob: Job? = null
    private var loadGeneration = 0L
    private var bllArtistScoreMap = emptyMap<String, Double>()
    private var artistDominantDecades = emptyMap<String, Int>()
    private var artistByUri = emptyMap<String, Artist>()
    private var smartArtistScoreMap = emptyMap<String, Double>()
    private var excludedArtistUris = emptySet<String>()
    private var excludedTrackUris = emptySet<String>()
    // Raw names of blocked artists, always applied (blocking is explicit, not gated by Smart Listening).
    private var blockedArtistNames = emptySet<String>()
    private var lastRadioStartAtMs = 0L
    private var lastRadioStartGenre: String? = null
    private var lastSmartMixSelection = emptyList<Track>()
    // Rolling history of the last few generated mixes' track URIs. Used to
    // soft-exclude tracks from very recent mixes so back-to-back generations
    // do not keep resurfacing the same tracks (e.g. "One Sentence Supervisor
    // - Object Subject" in three consecutive mixes across different genres).
    // Bounded so the exclusion window is recent, not "forever".
    private val recentSmartMixHistory: ArrayDeque<Set<String>> = ArrayDeque()
    // Parallel rolling history of which ARTISTS each recent mix surfaced.
    // Track-level rotation alone does not match the user's perception of
    // variety: cycling to different Toto cuts each mix still feels like
    // "Toto again". Soft-penalising recent artists drops them several slots
    // in the next ordering without permanently locking them out, so they
    // fade for a couple of mixes and return naturally afterwards.
    private val recentSmartMixArtists: ArrayDeque<Set<String>> = ArrayDeque()
    // Rolling history of which GENRES the last few mixes picked. SmartMix's
    // weighted-random genre selection is dominated by the user's top-2 BLL
    // genres (often "rock" + "alternative rock") so consecutive mixes keep
    // landing on the same family. Excluding these recent picks from the next
    // selection forces variety across listening sessions instead of
    // re-rolling the same dice.
    private val recentSmartMixGenres: ArrayDeque<String> = ArrayDeque()

    // User Smart Mix tuning knobs (all 0..1), collected from settings and read
    // per mix build. Variety -> per-artist track pool + jitter, discovery ->
    // exploration/adjacent breadth, length -> track-count target. 0.5 = neutral.
    @Volatile private var smartMixVariety: Double = 0.5
    @Volatile private var smartMixDiscovery: Double = 0.5
    @Volatile private var smartMixLength: Double = 0.5
    @Volatile private var smartMixStrictness: Double = 0.5

    // Single-flight background job that warms the expansion-artist cache (URIs +
    // catalogues) for the next high-discovery mix. See scheduleExpansionPrefetch.
    @Volatile private var expansionPrefetchJob: Job? = null

    init {
        autoConnect()
        viewModelScope.launch {
            settingsRepository.smartMixVariety.collect { v -> smartMixVariety = v.toDouble() }
        }
        viewModelScope.launch {
            settingsRepository.smartMixDiscovery.collect { v -> smartMixDiscovery = v.toDouble() }
        }
        viewModelScope.launch {
            settingsRepository.smartMixLength.collect { v -> smartMixLength = v.toDouble() }
        }
        viewModelScope.launch {
            settingsRepository.smartMixStrictness.collect { v -> smartMixStrictness = v.toDouble() }
        }
        viewModelScope.launch {
            loadFromCache()
            observeConnection()
        }
        viewModelScope.launch {
            observeMediaEvents()
        }
        viewModelScope.launch {
            sessionEventBus.resets.collect { resetForAccountSwitch() }
        }
        viewModelScope.launch {
            shortcutDispatcher.pendingAction
                .filterNotNull()
                .collect { action ->
                    when (action) {
                        is ShortcutAction.SmartMix -> {
                            shortcutDispatcher.consume()
                            makePlaylistForMe()
                        }
                        is ShortcutAction.GenreRadio -> {
                            shortcutDispatcher.consume()
                            startGenreRadio(action.genre)
                        }
                        else -> {}
                    }
                }
        }
    }

    private fun resetForAccountSwitch() {
        Log.d(TAG, "Session reset: dropping in-memory discover state")
        fullLoadJob?.cancel()
        radioStartJob?.cancel()
        connectionProbeJob?.cancel()
        mediaEventJob?.cancel()
        loadGeneration++
        genreArtists = emptyMap()
        strictGenreArtists = emptyMap()
        bllArtistScoreMap = emptyMap()
        artistDominantDecades = emptyMap()
        artistByUri = emptyMap()
        smartArtistScoreMap = emptyMap()
        excludedArtistUris = emptySet()
        excludedTrackUris = emptySet()
        lastRadioStartAtMs = 0L
        lastRadioStartGenre = null
        lastSmartMixSelection = emptyList()
        recentSmartMixHistory.clear()
        recentSmartMixArtists.clear()
        recentSmartMixGenres.clear()
        cacheStale = true
        _uiState.value = DiscoverUiState(isLoading = false)
    }

    private fun autoConnect() {
        viewModelScope.launch {
            wsClient.startupReady.first { it }
            if (wsClient.connectionState.value is ConnectionState.Disconnected && !wsClient.userDisconnected) {
                val url = settingsRepository.serverUrl.first()
                val token = settingsRepository.authToken.first()
                if (url.isNotBlank() && token.isNotBlank() && url.contains("://")) {
                    wsClient.connect(url, token)
                }
            }
        }
    }

    private suspend fun loadFromCache() {
        val cached = discoverCache.load()
        if (cached == null) {
            _uiState.value = _uiState.value.copy(isLoading = false)
            return
        }
        cachedTopArtists = cached.topArtists
        artistByUri = cached.topArtists.mapNotNull { artist ->
            artist.canonicalKey()?.let { it to artist }
        }.toMap()
        val historyGenreArtists = try {
            genreRepository.genreArtistMap()
        } catch (_: Exception) {
            emptyMap()
        }
        // buildGenreData here is only to seed the in-memory genre maps that Smart Mix
        // / Genre Radio consult before the first full load; the genre items shown on
        // screen come from the cached sections, so the built items are discarded.
        val (_, builtGenreArtists, builtStrictGenreArtists) =
            contentLoader.buildGenreData(cached.topArtists, historyGenreArtists, artistByUri)
        strictGenreArtists = builtStrictGenreArtists
        genreArtists = builtGenreArtists
        // Stale-while-revalidate: show the last rendered screen instantly (recent
        // grids included, as a placeholder) and let the first-connect refresh
        // replace it with live data.
        _uiState.value = _uiState.value.copy(
            sections = cached.sections,
            isLoading = false
        )
        val hasMissingImages = cached.sections.any { section ->
            section is DiscoverSection.AlbumSection && section.albums.any { it.imageUrl == null }
        }
        cacheStale = discoverCache.isStale(cached) || hasMissingImages
    }

    private suspend fun observeConnection() {
        var firstConnect = true
        wsClient.connectionState.collect { state ->
            if (state is ConnectionState.Connected) {
                // The disk cache is for INSTANT display on launch, not for skipping
                // the launch refresh: force the first connect-driven load past the
                // "cache fresh" gate so time-sensitive sections (Recently Played)
                // are live. Later reconnects keep the dedup/cooldown behaviour.
                loadFromServer(force = firstConnect)
                firstConnect = false
            }
        }
    }

    private suspend fun observeMediaEvents() {
        wsClient.events.collect { event ->
            when (event.event) {
                EventType.MEDIA_ITEM_UPDATED -> {
                    mediaEventJob?.cancel()
                    mediaEventJob = viewModelScope.launch {
                        delay(350)
                        if (wsClient.connectionState.value is ConnectionState.Connected) {
                            when (eventMediaType(event)) {
                                "track" -> refreshRecentFavoriteTracksSection()
                                "album" -> refreshFavoriteAlbumsAndRecentlyAddedTracksSections()
                                else -> {
                                    cacheStale = true
                                    loadFromServer()
                                }
                            }
                        }
                    }
                }
                EventType.MEDIA_ITEM_ADDED,
                EventType.MEDIA_ITEM_DELETED -> {
                    mediaEventJob?.cancel()
                    mediaEventJob = viewModelScope.launch {
                        delay(500)
                        if (wsClient.connectionState.value is ConnectionState.Connected) {
                            refreshFavoriteAlbumsAndRecentlyAddedTracksSections()
                        }
                    }
                }
            }
        }
    }

    private fun eventMediaType(event: net.asksakis.massdroidv2.data.websocket.ServerEvent): String? {
        val root = event.data?.jsonObject ?: return null
        val direct = root["media_type"]?.jsonPrimitive?.contentOrNull
        if (!direct.isNullOrBlank()) return direct.lowercase()

        val nested = root["media_item"]?.jsonObject?.get("media_type")?.jsonPrimitive?.contentOrNull
        if (!nested.isNullOrBlank()) return nested.lowercase()

        return null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshRecentFavoriteTracksSection() {
        val freshTracks = loadRecentFavoriteTracks()

        _uiState.value = _uiState.value.copy(
            sections = sectionCoordinator.updateRecentFavoriteTracks(
                current = _uiState.value.sections,
                tracks = freshTracks
            )
        )
        persistDisplayedSections()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshFavoriteAlbumsAndRecentlyAddedTracksSections() {
        val freshAlbums = contentLoader.loadRecentFavoriteAlbums()

        _uiState.value = _uiState.value.copy(
            sections = sectionCoordinator.updateRecentFavoriteAlbums(
                current = _uiState.value.sections,
                albums = freshAlbums
            )
        )
        persistDisplayedSections()
    }

    // Persist the currently rendered screen as the launch placeholder. Called after
    // a full load and after each WS-event section refresh so the cache mirrors what
    // the user last saw - the next launch shows it instantly, no manual refresh.
    private suspend fun persistDisplayedSections() {
        discoverCache.save(
            DiscoverCache.CacheData(
                sections = _uiState.value.sections,
                topArtists = cachedTopArtists
            )
        )
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        val connState = wsClient.connectionState.value
        Log.d(TAG, "refresh() called, connection=$connState")
        viewModelScope.launch {
            if (connState is ConnectionState.Connected) {
                musicRepository.requestLibrarySync(force = true)
                loadFromServer(isManualRefresh = true)
            } else {
                Log.d(TAG, "refresh() skipped: not connected")
            }
        }
    }

    fun makePlaylistForMe() {
        if (_uiState.value.isBuildingSmartMix) return
        val queueId = playerRepository.selectedPlayer.value?.playerId
        if (queueId.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(smartMixMessage = "Select a player first")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBuildingSmartMix = true)
            try {
                refreshSmartFiltersForMix()
                ensureBllArtistScoresLoaded()
                ensureArtistDecadesLoaded()
                val mixResult = buildSmartMixTracks()
                val trackUris = mixResult.tracks.map { it.uri }
                if (trackUris.size < SMART_MIX_MIN_TRACKS) {
                    _uiState.value = _uiState.value.copy(
                        smartMixMessage = "Not enough listening data for a solid mix yet"
                    )
                    return@launch
                }
                playGeneratedMix(queueId, mixResult.tracks, mixResult.genre, "smartMix")
                val message = if (mixResult.genre != null) {
                    val displayGenre = mixResult.genre.replaceFirstChar { it.uppercase() }
                    "$displayGenre mix ready (${trackUris.size} tracks)"
                } else {
                    "Smart mix ready (${trackUris.size} tracks)"
                }
                _uiState.value = _uiState.value.copy(smartMixMessage = message)
            } catch (e: Exception) {
                Log.e(TAG, "makePlaylistForMe failed", e)
                _uiState.value = _uiState.value.copy(
                    smartMixMessage = "Failed to generate smart mix"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isBuildingSmartMix = false)
            }
        }
    }

    fun clearSmartMixMessage() {
        _uiState.value = _uiState.value.copy(smartMixMessage = null)
    }

    // Shared play path for a generated finite mix (Smart Mix + seed-track Genre
    // Radio): mark the queue smart-generated, record recent history/artists/genre
    // for the next round's cool-down, then replace the queue with DSTM disabled
    // so the server cannot inject off-list tracks mid-replace.
    private suspend fun playGeneratedMix(
        queueId: String,
        tracks: List<Track>,
        pickedGenre: String?,
        logSource: String
    ) {
        playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.SMART_GENERATED)
        lastSmartMixSelection = tracks
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
        // EXPERIMENT: no longer disabling DSTM around the replace. On MA 2.9.1 the
        // replace is atomic (PR #3753), so DSTM does not inject into the curated
        // list mid-replace; it only continues AFTER the last curated track, which
        // is desirable. Removing the disable/restore also fixes DSTM getting stuck
        // OFF when this coroutine is cancelled before the restore runs.
        musicRepository.playMedia(
            queueId = queueId,
            uris = tracks.map { it.uri },
            option = "replace",
            awaitResponse = true
        )
        logQueueContents(logSource, awaitQueueItems = true)
    }

    fun startContinuousConnectionProbe(sampleCount: Int = CONNECTION_PING_SAMPLES) {
        connectionProbeJob?.cancel()
        connectionProbeJob = viewModelScope.launch {
            while (true) {
                val currentState = wsClient.connectionState.value
                if (currentState !is ConnectionState.Connected) {
                    _uiState.value = _uiState.value.copy(
                        connectionProbe = _uiState.value.connectionProbe.copy(
                            inProgress = false,
                            samplesMs = emptyList(),
                            failedSamples = 0,
                            error = "Not connected",
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                    delay(CONNECTION_PING_INTERVAL_MS)
                    continue
                }

                _uiState.value = _uiState.value.copy(
                    connectionProbe = _uiState.value.connectionProbe.copy(
                        inProgress = true,
                        error = null
                    )
                )

                val samples = mutableListOf<Long>()
                var failed = 0
                val probeCommand = "players/all"
                val probeMethod = "WS RPC"
                repeat(sampleCount.coerceAtLeast(1)) { index ->
                    if (index > 0) delay(140)
                    val startNs = System.nanoTime()
                    try {
                        wsClient.sendCommand(
                            command = probeCommand,
                            awaitResponse = true,
                            timeoutMs = CONNECTION_PING_TIMEOUT_MS
                        )
                        samples += ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                    } catch (e: Exception) {
                        failed++
                        Log.w(TAG, "probeConnection failed: ${e.message}")
                    }
                }

                val updatedHistory = (
                    _uiState.value.connectionProbe.historyMs +
                        samples +
                        List(failed) { 0L }
                    ).takeLast(CONNECTION_PING_HISTORY_LIMIT)

                _uiState.value = _uiState.value.copy(
                    connectionProbe = ConnectionProbeState(
                        inProgress = false,
                        samplesMs = samples,
                        historyMs = updatedHistory,
                        failedSamples = failed,
                        probeMethod = probeMethod,
                        error = if (samples.isEmpty()) "Ping failed" else null,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                delay(CONNECTION_PING_INTERVAL_MS)
            }
        }
    }

    fun stopContinuousConnectionProbe() {
        connectionProbeJob?.cancel()
        connectionProbeJob = null
        _uiState.value = _uiState.value.copy(
            connectionProbe = _uiState.value.connectionProbe.copy(inProgress = false)
        )
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

    // Track-count target from the user "length" knob (see SMART_MIX_TARGET_*).
    private fun smartMixTrackTarget(): Int =
        (SMART_MIX_TARGET_MIN + smartMixLength * SMART_MIX_TARGET_SPAN).toInt()

    private suspend fun buildSeedTrackMix(): SmartMixResult =
        SmartMixResult(
            seedTrackMixGenerator.buildSmartMix(seedTuning(), smartMixTrackTarget(), currentRecency()),
            null
        )

    // Finite seed-track Genre Radio mix; empty if too few in-genre seeds (caller
    // falls back to the server radio).
    private suspend fun buildGenreRadioSeedMix(genre: String): List<Track> =
        seedTrackMixGenerator.buildGenreRadio(genre, seedTuning(), smartMixTrackTarget(), currentRecency())

    private fun seedTuning() =
        SeedTrackMixGenerator.Tuning(smartMixVariety, smartMixDiscovery, smartMixStrictness)

    // Recent-mix cool-down context: which tracks/artists surfaced in the last few
    // mixes (so the generator can de-rank them) plus persistent exclusions.
    private fun currentRecency() = SeedTrackMixGenerator.Recency(
        excludedTrackUris = excludedTrackUris,
        recentArtistCounts = recentSmartMixArtists.flatten().groupingBy { it }.eachCount(),
        recentMixTrackUris = recentSmartMixHistory.flatten().toSet(),
        blockedArtistNames = blockedArtistNames
    )

    // Smart Mix orchestrator: try the seed-track generator first (coherent,
    // track-level similarity). Only if it cannot produce a solid mix (cold
    // cache, no Last.fm key, or too few resolved candidates) do we fall back to
    // the legacy artist/genre engine.
    private suspend fun buildSmartMixTracks(): SmartMixResult {
        // Cold-start guard: the seed-track engine needs a Last.fm key (track-level
        // similars come from there). With no key it can only ever produce an empty
        // pool, so skip the attempt and go straight to the genre engine, which
        // works off MA + locally-enriched genres.
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

    private suspend fun buildGenreSmartMixTracks(): SmartMixResult {
        val mixSeed = System.currentTimeMillis()
        val random = kotlin.random.Random(mixSeed)
        // Soft-exclude tracks that were in the last few mixes so back-to-back
        // generations don't keep resurfacing the same hits (e.g. "One Sentence
        // Supervisor - Object Subject" in three consecutive mixes across
        // different genres). Persistent exclusions (blocked / suppressed)
        // still apply on top.
        val recentMixTrackUris = recentSmartMixHistory.flatten().toSet()
        val mixExcludedTrackUris = excludedTrackUris + recentMixTrackUris
        // Per-artist appearance penalty drawn from the last two mixes. Each
        // appearance subtracts a fixed amount from the artist's effective
        // score, so persistent re-entries (e.g. Toto in every rock mix)
        // cool down for a couple of generations rather than continuously
        // dominating just because their BLL/recent score is high.
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
        // Build a per-mix penalised view of the BLL map. The class-level
        // bllArtistScoreMap is cached and shared across mixes; we never mutate
        // it. The penalised copy applies the same recency cool-down to artists
        // who only appear in long-tail BLL but not in the recent 120-day
        // artistScores set, otherwise they would dodge the penalty entirely.
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

        // Daypart boost per genre: average daypart affinity of the genre's artists
        val genreDaypartBoost = mutableMapOf<String, Double>()
        for ((genre, artistKeys) in mixGenreArtists) {
            val affinities = artistKeys.mapNotNull { daypartAffinity[it] }
            if (affinities.isNotEmpty()) {
                genreDaypartBoost[genre] = affinities.average()
            }
        }

        // Combine BLL genre scores with daypart boost
        val timeAwareGenreScores = blendedGenreScores.map { gs ->
            val genre = normalizeGenre(gs.genre)
            val dayBoost = genreDaypartBoost[genre] ?: 0.0
            GenreScore(genre, gs.score + dayBoost * DAYPART_GENRE_BOOST_WEIGHT)
        }.sortedByDescending { it.score }

        // Apply genre cooldown: drop the last few picked genres so consecutive
        // mixes do not all keep landing on rock/alternative-rock just because
        // those have the largest BLL share. If filtering leaves zero candidates
        // (very narrow listening profile), fall back to the unfiltered list so
        // we always produce something.
        val recentlyPickedGenres = recentSmartMixGenres.toSet()
        val genrePool = timeAwareGenreScores
            .filter { normalizeGenre(it.genre) !in recentlyPickedGenres }
            .ifEmpty { timeAwareGenreScores }
        // Try up to 5 genres, pick via weighted random, fallback if too few tracks.
        // Wider window lets secondary-but-loved genres surface instead of always
        // looping through the top 3.
        val triedGenres = mutableSetOf<String>()
        for (candidate in genrePool.take(5)) {
            val pickedGenre = if (triedGenres.isEmpty()) {
                weightedRandomGenre(
                    genrePool.filter { it.genre !in triedGenres },
                    random
                ) ?: continue
            } else {
                // Second/third attempt: pick next best untried genre deterministically
                candidate.genre.takeIf { it !in triedGenres } ?: continue
            }
            triedGenres.add(pickedGenre)

            // Discovery controls genre breadth: in the comfort band stay strictly
            // on the picked genre (max consistency, e.g. pure heavy metal); above
            // it, allow adjacent genres in.
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

        // Fallback: original multi-genre behavior
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

        // Even in the multi-genre fallback, anchor tracks to the user's top
        // genres so a mix never picks up an off-vibe cut (e.g. a downtempo
        // interlude inside a metal set). Tagged tracks must overlap the accepted
        // set; untagged tracks pass (cannot be validated, and this is the last
        // resort). Discovery widens the accepted set to adjacent genres.
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

    // Accepted-genre set for the multi-genre fallback: the user's top genres,
    // widened to adjacent genres only when discovery is past the comfort band.
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

        // Genre-track filter. Untagged tracks are KEPT only for EXACT-genre
        // artists (those in this genre's own pool), whose untagged cuts are
        // overwhelmingly in-genre. For adjacent / Last.fm-expansion artists we
        // require an explicit tag match, because an untagged track there can be
        // anything (e.g. a rap track from a wrongly-pulled artist landing in a
        // synthpop mix). Tagged tracks must always match the picked/adjacent set.
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

        // Fill from the user's own genre artists first. Estimate the REAL yield:
        // each artist contributes at most MIX_MAX_TRACKS_PER_ARTIST to the final
        // mix (not its whole catalogue), so counting raw tracks would wrongly skip
        // expansion while still far short of the target. Last.fm expansion is the
        // slow network step, so only pay for it when the local pool can't reach
        // the target, or discovery is high enough that the user wants more.
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
            // INLINE: cache-only. Use already-resolved similar URIs (no live MA
            // search) and only their already-cached catalogues (cacheOnly). The
            // slow search + catalogue fetch happens in the background prefetch
            // below, so the mix the user sees never blocks on the network.
            val expansionArtists = resolveLastFmExpansionArtists(
                seedArtistUris = artistOrder,
                excludedArtistUris = excludedArtistUris,
                allowProviderSearch = false
            )
            val seenKeys = artistOrder
                .mapNotNull { MediaIdentity.artistKeyFromUri(it) ?: it.takeIf { it.isNotBlank() } }
                .toMutableSet()
            // Splice expansion artists right after the top-2 anchor slots so they
            // are reachable (the interleaver fills the target from the front).
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

        // Background: warm the cache (resolve similar -> provider URI, fetch their
        // catalogues) so the NEXT high-discovery mix can include them instantly.
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

    @Suppress("TooGenericExceptionCaught")
    private fun loadFromServer(isManualRefresh: Boolean = false, force: Boolean = false) {
        if (net.asksakis.massdroidv2.BuildConfig.ENABLE_UPDATE_CHECK) {
            viewModelScope.launch {
                try {
                    val beta = settingsRepository.includeBetaUpdates.first()
                    appUpdateChecker.checkForUpdates(force = false, includePrerelease = beta)
                } catch (_: Exception) { /* best-effort */ }
            }
        }
        if (!isManualRefresh && !force && !cacheStale && _uiState.value.sections.isNotEmpty()) {
            Log.d(TAG, "loadFromServer: skipped (cache fresh)")
            return
        }
        if (!isManualRefresh && fullLoadJob?.isActive == true) {
            Log.d(TAG, "loadFromServer: skipped (in flight)")
            return
        }
        if (!isManualRefresh && !force && System.currentTimeMillis() - lastSuccessfulLoadAt < LOAD_COOLDOWN_MS) {
            Log.d(TAG, "loadFromServer: skipped (cooldown)")
            return
        }
        val generation = ++loadGeneration
        fullLoadJob?.cancel()
        fullLoadJob = viewModelScope.launch {
            Log.d(TAG, "loadFromServer: starting (manual=$isManualRefresh)")
            if (isManualRefresh) {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
            }
            try {
                val content = contentLoader.load(excludedArtistUris = excludedArtistUris)
                val merged = content.mergedArtists
                artistByUri = merged.mapNotNull { artist ->
                    artist.canonicalKey()?.let { it to artist }
                }.toMap()
                lastFmLibraryEnricher.enrichInBackground(merged)

                val smartListeningEnabled = settingsRepository.smartListeningEnabled.first()
                if (smartListeningEnabled) {
                    val blocked = smartListeningRepository.getBlockedArtistUris()
                    val suppressed = smartListeningRepository.getSuppressedArtistUris(days = 120)
                    val metrics = smartListeningRepository.getArtistMetrics(days = 120)
                    excludedArtistUris = blocked + suppressed
                    excludedTrackUris = smartListeningRepository.getSuppressedTrackUris()
                    smartArtistScoreMap = metrics.mapValues { it.value.score }
                } else {
                    excludedArtistUris = emptySet()
                    excludedTrackUris = emptySet()
                    smartArtistScoreMap = emptyMap()
                }
                val smartFilteredArtists = if (excludedArtistUris.isEmpty()) {
                    merged
                } else {
                    merged.filterNot { isArtistExcluded(it, excludedArtistUris) }
                }
                strictGenreArtists = content.strictGenreArtists
                    .mapValues { (_, uris) ->
                        uris.filterNot { uri ->
                            val key = MediaIdentity.artistKeyFromUri(uri)
                            key != null && key in excludedArtistUris
                        }
                    }
                genreArtists = content.genreArtists
                    .mapValues { (_, uris) ->
                        uris.filterNot { uri ->
                            val key = MediaIdentity.artistKeyFromUri(uri)
                            key != null && key in excludedArtistUris
                        }
                    }
                val genreItems = content.genreItems
                    .filter { it.name in genreArtists.keys }

                val discovery = try {
                    recommendationOrchestrator.buildDiscovery(
                        libraryArtists = merged,
                        serverFolders = content.enrichedFolders,
                        excludedArtistUris = excludedArtistUris
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to build discovery", e)
                    DiscoveryResult(emptyList(), emptyList())
                }
                val suggested = discovery.artists
                val discover: List<Album>? = discovery.albums.ifEmpty { null }
                Log.d(TAG, "Discovery artists: ${suggested.size}, albums: ${discovery.albums.size}")

                val bllGenreScores = try {
                    genreRepository.scoredGenres(days = 90, limit = 20)
                } catch (_: Exception) {
                    emptyList()
                }
                val bllArtistScores = try {
                    playHistoryRepository.getScoredArtists(days = 90, limit = BLL_ARTIST_SCORE_LIMIT)
                } catch (_: Exception) {
                    emptyList()
                }
                if (generation != loadGeneration) return@launch
                bllArtistScoreMap = bllArtistScores.toScoreMap()

                _uiState.value = _uiState.value.copy(
                    sections = sectionBuilder.buildSections(
                    serverFolders = content.enrichedFolders,
                    suggestedArtists = suggested,
                    suggestedAlbums = discover ?: emptyList(),
                    genreItems = genreItems,
                    bllGenreScores = bllGenreScores
                    )
                )
                Log.d(TAG, "Built ${_uiState.value.sections.size} sections")

                cacheStale = false
                lastSuccessfulLoadAt = System.currentTimeMillis()
                cachedTopArtists = merged
                persistDisplayedSections()
            } finally {
                if (generation == loadGeneration) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            }
        }
    }

    private fun buildGenreItemsFallback(
        artists: List<Artist>,
        historyGenreArtists: Map<String, List<String>> = emptyMap()
    ): List<GenreItem> {
        val (genreItems, builtGenreArtists, builtStrictGenreArtists) =
            contentLoader.buildGenreData(artists, historyGenreArtists, artistByUri)
        strictGenreArtists = builtStrictGenreArtists
        genreArtists = builtGenreArtists
        return genreItems
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadRecentFavoriteTracks(limit: Int = 10): List<Track> {
        val favoritesByAdded = try {
            musicRepository.getTracks(
                orderBy = "timestamp_added_desc",
                limit = limit * RECENT_TRACKS_QUERY_FACTOR,
                favoriteOnly = true
            )
        } catch (_: Exception) {
            emptyList()
        }

        return favoritesByAdded
            .filter { it.favorite }
            .distinctBy { it.canonicalKey() ?: it.uri }
            .take(limit)
    }

    fun startGenreRadio(genre: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        val nowMs = System.currentTimeMillis()
        val strictCandidates = filteredGenreCandidateUris(strictGenreArtists[genre])
        val broadCandidates = filteredGenreCandidateUris(genreArtists[genre])
        val baseCandidateUris = when {
            strictCandidates.size >= 4 -> strictCandidates
            strictCandidates.isNotEmpty() -> (strictCandidates + broadCandidates)
                .distinctBy { uri -> MediaIdentity.artistKeyFromUri(uri) ?: uri }
            else -> broadCandidates
        }
        if (baseCandidateUris.isEmpty()) {
            _uiState.value = _uiState.value.copy(smartMixMessage = "Not enough matching artists to start $genre radio")
            return
        }
        if (radioStartJob?.isActive == true) {
            Log.d(TAG, "startGenreRadio ignored: request already in flight")
            return
        }
        if (lastRadioStartGenre == genre && nowMs - lastRadioStartAtMs < GENRE_RADIO_SPAM_WINDOW_MS) {
            Log.d(TAG, "startGenreRadio ignored: spam guard for genre='$genre'")
            return
        }
        lastRadioStartGenre = genre
        lastRadioStartAtMs = nowMs
        val wasPlayingBefore = playerRepository.selectedPlayer.value?.state == PlaybackState.PLAYING
        val baselineTrackUri = playerRepository.queueState.value?.currentItem?.track?.uri

        radioStartJob = viewModelScope.launch {
            try {
                refreshSmartFiltersForMix()
                ensureBllArtistScoresLoaded()
                ensureArtistDecadesLoaded()
                _uiState.value = _uiState.value.copy(radioOverlayGenre = genre)

                // Primary path: a finite, coherent seed-track radio for this
                // genre (same engine as Smart Mix, seeded from the user's
                // in-genre tracks). Falls through to the server radio below when
                // there are too few in-genre seeds or the pool cannot resolve.
                val seedMix = buildGenreRadioSeedMix(genre)
                if (seedMix.size >= SMART_MIX_MIN_TRACKS) {
                    playGeneratedMix(queueId, seedMix, genre, "genreRadio[$genre]")
                    _uiState.value = _uiState.value.copy(
                        radioOverlayGenre = null,
                        smartMixMessage = "${genre.replaceFirstChar { it.uppercase() }} radio ready (${seedMix.size} tracks)"
                    )
                    return@launch
                }
                Log.d(TAG, "Genre radio '$genre': seed-track yielded ${seedMix.size} (<$SMART_MIX_MIN_TRACKS), using server radio")

                val focusDecade = loadGenreRadioFocusDecade(genre, baseCandidateUris)
                val rankedUris = rankGenreRadioArtistUris(baseCandidateUris, focusDecade)
                val libraryUris = prioritizeDecadeCoherentUris(rankedUris, focusDecade)
                    .take(MAX_GENRE_RADIO_ARTIST_URIS)
                if (libraryUris.isEmpty()) return@launch

                val discoveryUris = resolveDiscoverySeeds(libraryUris, genre)
                val allSeeds = (libraryUris + discoveryUris).distinct()

                Log.d(
                    TAG,
                    "startGenreRadio: genre='$genre', strict=${strictCandidates.size}, " +
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
                val requestAccepted = startGenreRadioWithFallback(
                    queueId = queueId,
                    genre = genre,
                    rankedUris = allSeeds
                )
                if (!requestAccepted) {
                    Log.w(TAG, "startGenreRadio: all seed attempts failed for genre='$genre'")
                    _uiState.value = _uiState.value.copy(
                        radioOverlayGenre = null,
                        smartMixMessage = "Failed to start $genre radio"
                    )
                    return@launch
                }
                val started = waitForGenreRadioStart(
                    wasPlayingBefore = wasPlayingBefore,
                    baselineTrackUri = baselineTrackUri
                )
                if (!started) {
                    Log.w(TAG, "startGenreRadio: start confirmation timeout for genre='$genre'")
                    _uiState.value = _uiState.value.copy(
                        radioOverlayGenre = null,
                        smartMixMessage = "$genre radio did not start in time"
                    )
                    return@launch
                }
                sanitizeGenreRadioQueue(queueId, genre)
                logQueueContents("genreRadio[$genre]", awaitQueueItems = true)
                _uiState.value = _uiState.value.copy(radioOverlayGenre = null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start genre radio", e)
                _uiState.value = _uiState.value.copy(
                    radioOverlayGenre = null,
                    smartMixMessage = "Failed to start $genre radio"
                )
            } finally {
                radioStartJob = null
            }
        }
    }

    /**
     * Pull artists that Last.fm marks as similar to the current pool's top seeds.
     * Owned (library) similars are used first (no round-trip); if they don't fill
     * the budget, genuinely-new similars are resolved to a provider URI via MA
     * search so the mix can discover beyond what the user already owns. Genre
     * coherence is enforced downstream by the track-level filter.
     */
    @Suppress("TooGenericExceptionCaught")
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

        // Build a cheap name->uri lookup over the user's library so we can
        // map Last.fm names back to playable URIs without server round-trips.
        val nameToUri = artistByUri.entries
            .associate { (uri, artist) -> artist.name.lowercase() to uri }
        if (nameToUri.isEmpty()) return emptyList()

        // Dedupe by NAME, not URI: an artist may exist under multiple
        // canonical URIs (e.g. library://artist/18 and
        // deezer--abc://artist/2483 both for Alice in Chains). If we only
        // checked URI uniqueness we would inject a second AiC entry from
        // Last.fm and end up with 4 AiC tracks (cap doubled across two
        // buckets). Names are the only stable identity across providers.
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
                    // Not in the library: a genuinely new artist. Remember it for a
                    // provider search below so expansion can discover beyond what
                    // the user already owns (the old code dropped these entirely).
                    newNames += similar.name
                    alreadyInPoolNames += similar.name
                }
            }
        }

        // Owned matches alone rarely fill the target (and they carry the same few
        // owned tracks). Resolve genuinely-new similar artists via a provider
        // search so they bring a full streaming catalogue. This costs network
        // round-trips, so it's gated to high discovery; the dynamic per-artist cap
        // keeps the default (owned-only) path full without it. Bounded-parallel so
        // the extra round-trips don't serialise the build.
        if (collected.size < targetCount && newNames.isNotEmpty()) {
            val gate = Semaphore(ARTIST_FETCH_CONCURRENCY)
            // Cache hits are always free. Live MA search (the slow part) only runs
            // when explicitly allowed (the background prefetch), so the inline mix
            // build uses cached resolutions only and never blocks on a search.
            var searchBudget = if (allowProviderSearch) SMART_MIX_MAX_SEARCHES else 0
            // Resolve new artists to a provider URI. Cache hits are free; only
            // cache misses spend the (slow) MA search budget, and every resolution
            // is cached so later mixes skip the search entirely.
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

    // Background prefetch: resolve similar artists to provider URIs (live search)
    // and warm their catalogue cache, so the NEXT high-discovery mix can include
    // them with zero network wait. Single-flight: ignored while one is running.
    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpansionPrefetch(
        seedArtistUris: List<String>,
        excludedArtistUris: Set<String>
    ) {
        if (expansionPrefetchJob?.isActive == true) return
        expansionPrefetchJob = viewModelScope.launch {
            try {
                val artists = resolveLastFmExpansionArtists(
                    seedArtistUris = seedArtistUris,
                    excludedArtistUris = excludedArtistUris,
                    allowProviderSearch = true
                )
                if (artists.isNotEmpty()) {
                    // cacheOnly = false performs (and caches) the catalogue fetch.
                    // Low concurrency keeps this background job a polite citizen on
                    // the MA server (Last.fm is already globally rate-limited).
                    fetchTracksByArtist(artists, concurrency = PREFETCH_CONCURRENCY) { _, tracks -> tracks }
                    Log.d(TAG, "Expansion prefetch: warmed ${artists.size} artists")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Expansion prefetch failed: ${e.message}")
            }
        }
    }

    // Resolve a (not-in-library) artist name to a playable provider URI via MA
    // search, preferring an exact name match, and cache the result so later mixes
    // skip the slow search. Used by Last.fm expansion to bring in new artists.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun searchAndCacheArtistUri(name: String): String? {
        return try {
            // MA search has no server-side per-provider timeout: one slow provider
            // can hang the whole call for minutes. Bound it so a stuck search can
            // never stall the mix; a miss just means this artist is skipped.
            val result = withTimeoutOrNull(SMART_MIX_SEARCH_TIMEOUT_MS) {
                musicRepository.search(
                    query = name,
                    mediaTypes = listOf(MediaType.ARTIST),
                    limit = SMART_MIX_SEARCH_LIMIT
                )
            } ?: return null
            // Require an EXACT (case-insensitive) name match. The old fuzzy
            // fallback to "first result" pulled in the wrong artist when the name
            // didn't resolve, e.g. similar "Blackbook" (synthpop) returning
            // "Black Book" (rap) and contaminating the mix. Better to skip an
            // unresolved name than inject an off-genre artist.
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
            "resolveDiscoverySeeds: genre-validated ${validatedNames.size}/${discoveryNames.size} " +
                "for '$genre'"
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
        return error.code == 999 && "year 0 is out of range" in msg
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sanitizeGenreRadioQueue(queueId: String, genre: String) {
        try {
            val items = musicRepository.getQueueItems(queueId)
            if (items.size < 2) return

            val artistGenreMap = resolveQueueArtistGenres(items)
            val normalizedTarget = normalizeGenre(genre)
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
                "sanitizeGenreRadioQueue[$genre]: removed ${removeQueueItemIds.size} items " +
                    "($offGenreCount off-genre)"
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

    companion object {
        private val GENRE_FAMILIES = listOf(
            // Folk / Acoustic / Americana / Country
            setOf("folk", "indie folk", "folk rock", "americana", "acoustic",
                "singer songwriter", "country", "alt country", "neofolk"),
            // Indie / Alternative
            setOf("indie", "indie rock", "indie pop", "alternative",
                "alternative rock", "dream pop", "shoegaze", "lo fi",
                "britpop", "post rock"),
            // Rock
            setOf("rock", "alternative rock", "classic rock", "hard rock",
                "garage rock", "grunge", "psychedelic rock", "progressive rock",
                "stoner rock", "southern rock", "blues rock", "glam rock",
                "noise rock", "space rock", "art rock", "soft rock", "rockabilly"),
            // Pop / Electropop / Synthpop
            setOf("pop", "pop rock", "electropop", "synthpop", "synth pop",
                "indie pop", "new wave"),
            // Electronic Dance (club)
            setOf("house", "deep house", "tech house", "techno", "minimal",
                "trance", "progressive trance", "dance", "drum and bass",
                "dubstep", "breakbeat", "club", "disco", "electro", "psytrance"),
            // Electronic (broad, bridges pop-electronic and dance-electronic)
            setOf("electronic", "electronica", "electropop", "synthpop",
                "synth pop", "house", "techno", "dance"),
            // Ambient / Downtempo / Chill
            setOf("ambient", "downtempo", "chillout", "trip hop", "lounge",
                "new age", "idm", "electronica", "dark ambient", "drone"),
            // Darkwave / Goth / Industrial
            setOf("darkwave", "dark electro", "ebm", "goth", "gothic rock",
                "gothic metal", "industrial", "industrial rock",
                "industrial metal", "post punk", "new wave"),
            // Hip Hop / R&B / Soul
            setOf("hip hop", "rap", "rnb", "rhythm and blues", "soul", "funk",
                "underground hip hop"),
            // Metal
            setOf("metal", "heavy metal", "death metal", "black metal",
                "doom metal", "thrash metal", "progressive metal", "metalcore",
                "post metal", "sludge", "symphonic metal", "power metal",
                "speed metal", "industrial metal", "alternative metal",
                "nu metal", "folk metal", "gothic metal", "viking metal",
                "melodic death metal", "melodic metal", "deathcore",
                "grindcore", "technical death metal", "brutal death metal",
                "atmospheric black metal", "depressive black metal"),
            // Punk / Hardcore
            setOf("punk", "punk rock", "hardcore", "hardcore punk",
                "post hardcore", "pop punk", "screamo", "emo",
                "melodic hardcore"),
            // Jazz / Blues
            setOf("jazz", "blues", "smooth jazz", "acid jazz", "jazz fusion",
                "fusion", "swing", "nu jazz", "soul", "funk"),
            // Classical
            setOf("classical", "soundtrack", "instrumental",
                "contemporary classical", "neoclassical", "baroque"),
            // Reggae / Ska
            setOf("reggae", "ska", "dub"),
            // World / Latin
            setOf("world", "celtic", "latin", "mpb")
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun logQueueContents(source: String, awaitQueueItems: Boolean = false) {
        try {
            val queueId = playerRepository.requireSelectedPlayerId() ?: return
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
            if (source == "smartMix" && lastSmartMixSelection.isNotEmpty()) {
                inspectFirstSmartMixMismatches(lastSmartMixSelection, items)
            }
        } catch (e: Exception) {
            Log.w(TAG, "logQueueContents failed: ${e.message}")
        }
    }

    private suspend fun inspectFirstSmartMixMismatches(
        expected: List<Track>,
        actual: List<net.asksakis.massdroidv2.domain.model.QueueItem>
    ) {
        val actualUris = actual.mapNotNull { it.track?.uri?.takeIf(String::isNotBlank) }
        val mismatches = mutableListOf<String>()
        val compareCount = minOf(expected.size, actualUris.size)
        repeat(compareCount) { index ->
            val expectedUri = expected[index].uri
            val actualUri = actualUris[index]
            if (expectedUri != actualUri && actualUris.indexOf(expectedUri) == -1) {
                mismatches += expectedUri
            }
        }
        mismatches.distinct().take(3).forEach { inspectSmartMixUri(it) }
    }

    private suspend fun inspectSmartMixUri(uri: String) {
        try {
            val item = wsClient.sendCommand(
                command = MaCommands.Music.ITEM_BY_URI,
                args = ItemByUriArgs(uri).toJson(),
                awaitResponse = true,
                timeoutMs = 5_000L
            )?.jsonObject ?: run {
                Log.w(TAG, "SmartMix uri probe: no item_by_uri payload for $uri")
                return
            }

            val resolvedUri = item["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val provider = item["provider"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val itemId = item["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val artists = (item["artists"] as? JsonArray)
                ?.mapNotNull { artistEl -> artistEl.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()
            Log.w(
                TAG,
                "SmartMix uri probe: requested=$uri resolved=$resolvedUri " +
                    "provider=$provider itemId=$itemId name='$name' artists='${artists.joinToString()}'"
            )
        } catch (e: Exception) {
            Log.w(TAG, "SmartMix uri probe failed for $uri: ${e.message}")
        }
    }

    private suspend fun awaitQueueItems(
        queueId: String,
        baselineTrackUri: String?
    ): List<net.asksakis.massdroidv2.domain.model.QueueItem> {
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

    fun playTrack(track: Track) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, track.uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play track", e)
            }
        }
    }

    private suspend fun getArtistAlbumsForIdentity(artistIdentity: String): List<Album> {
        for ((provider, itemId) in resolveArtistRefs(artistIdentity)) {
            try {
                val albums = musicRepository.getArtistAlbums(itemId, provider)
                if (albums.isNotEmpty()) return albums
            } catch (_: Exception) {
                // Try next candidate ref.
            }
        }
        return emptyList()
    }

    /**
     * Resolve tracks for each artist in [artistOrder] concurrently (bounded by
     * [ARTIST_FETCH_CONCURRENCY]), applying [transform] to each artist's tracks,
     * and return only the artists that yielded a non-empty result. Replaces a
     * strictly-sequential loop so one slow provider artist no longer serialises
     * the whole build. Result map order is irrelevant (the caller orders by
     * [artistOrder]).
     */
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

    // Sample up to MAX_TRACKS_PER_ARTIST tracks from a (possibly large) catalogue.
    // The MA artist_tracks endpoint returns a deterministic order (album /
    // popularity), so a plain take(N) would always serve the same head-of-list
    // tracks and bury the rest for the whole cache window. A seeded shuffle keeps
    // the subset representative of the discography while staying reproducible.
    private fun sampleTracksForMix(tracks: List<Track>, seed: Long): List<Track> =
        if (tracks.size <= MAX_TRACKS_PER_ARTIST) {
            tracks
        } else {
            tracks.shuffled(kotlin.random.Random(seed)).take(MAX_TRACKS_PER_ARTIST)
        }

    private suspend fun getArtistTracksForIdentity(
        artistIdentity: String,
        cacheOnly: Boolean = false
    ): List<Track> {
        val cacheKey = MediaIdentity.artistKeyFromUri(artistIdentity) ?: artistIdentity
        playHistoryRepository.getCachedArtistTracks(cacheKey, ARTIST_TRACK_CACHE_TTL_MS)?.let { cached ->
            if (cached.isNotEmpty()) {
                // Cap on READ too, not just on fill: entries cached before the cap
                // existed (or by other paths) can still hold the full catalogue, and
                // enriching hundreds of cached tracks is itself the slow step.
                // Sample (not head-of-list) so a prolific artist's deep cuts can
                // surface; stable per-artist seed keeps the enriched subset
                // consistent across reads within the cache window.
                val capped = sampleTracksForMix(cached, cacheKey.hashCode().toLong())
                Log.d(TAG, "Artist track cache hit: $cacheKey (${capped.size}/${cached.size} tracks)")
                return enrichTracksWithLastFmGenres(capped)
            }
        }
        // cacheOnly callers (inline mix build) must never pay the MA fetch: an
        // uncached artist is simply skipped and picked up later from cache once
        // the background prefetch has populated it.
        if (cacheOnly) return emptyList()
        for ((provider, itemId) in resolveArtistRefs(artistIdentity)) {
            try {
                val tracks = musicRepository.getArtistTracks(itemId, provider)
                if (tracks.isNotEmpty()) {
                    // Cap BEFORE enrichment: artist_tracks returns the full catalogue
                    // (MA ignores a limit arg), and enriching hundreds of tracks is
                    // what stalled the mix build on a prolific artist. Sample rather
                    // than head-of-list so the cached subset is representative of the
                    // whole discography, not just the first 40 by the server's order.
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

    private fun isArtistExcluded(artist: Artist, excludedKeys: Set<String>): Boolean {
        val key = artist.canonicalKey()
        return key != null && key in excludedKeys
    }

    private fun parseMediaUri(uri: String): Pair<String, String>? {
        val sep = uri.indexOf("://")
        if (sep < 0) return null
        val provider = uri.substring(0, sep)
        val itemId = uri.substringAfterLast("/")
        return if (provider.isNotBlank() && itemId.isNotBlank()) provider to itemId else null
    }
}
