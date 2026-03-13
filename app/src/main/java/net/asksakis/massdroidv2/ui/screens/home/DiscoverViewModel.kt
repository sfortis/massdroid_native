package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
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
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import net.asksakis.massdroidv2.ui.ShortcutAction
import net.asksakis.massdroidv2.ui.ShortcutActionDispatcher
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
private const val GENRE_MIX_ARTIST_LIMIT = 30
private const val GENRE_MIX_TRACK_TARGET = 30
private const val GENRE_MIX_MIN_QUEUE_SIZE = 15
private const val GENRE_RADIO_ATTEMPT_POOL_SIZE = 20
private val GENRE_RADIO_BATCH_SIZES = listOf(12, 8, 4, 2, 1)
private const val GENRE_RADIO_EXPLORATION_COUNT = 4
private const val GENRE_RADIO_ALLOWED_DECADE_GAP = 10
private const val GENRE_RADIO_DECADE_LOOKBACK_DAYS = 720
private const val ARTIST_DECADE_LOOKBACK_DAYS = 720
private const val SMART_MIX_DAYPART_LOOKBACK_DAYS = 180
private const val SMART_MIX_RECENT_LOOKBACK_DAYS = 14
private const val SMART_MIX_TRACK_TARGET = 40
private const val DAYPART_GENRE_BOOST_WEIGHT = 2.0
private const val SMART_MIX_MIN_TRACKS = 8
private const val SMART_MIX_FAVORITES_QUERY_LIMIT = 500
private const val ARTIST_TRACK_CACHE_TTL_MS = 12 * 60 * 60 * 1000L
private const val GENRE_RADIO_DISCOVERY_SEEDS = 10
private const val GENRE_RADIO_SIMILAR_RESOLVE_LIMIT = 5
private const val GENRE_RADIO_SPAM_WINDOW_MS = 1_500L
private const val GENRE_RADIO_START_WAIT_TIMEOUT_MS = 8_000L
private const val CONNECTION_PING_SAMPLES = 3
private const val CONNECTION_PING_TIMEOUT_MS = 3_000L
private const val CONNECTION_PING_INTERVAL_MS = 1_200L
private const val CONNECTION_PING_HISTORY_LIMIT = 24

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

data class GenreItem(
    val name: String,
    val count: Int,
    val imageUrl: String?
)

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
    private val sectionBuilder: DiscoverSectionBuilder,
    private val lastFmSimilarResolver: net.asksakis.massdroidv2.data.lastfm.LastFmSimilarResolver,
    private val lastFmLibraryEnricher: net.asksakis.massdroidv2.data.lastfm.LastFmLibraryEnricher,
    private val lastFmGenreResolver: net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver,
    private val shortcutDispatcher: ShortcutActionDispatcher,
    private val appUpdateChecker: net.asksakis.massdroidv2.data.update.AppUpdateChecker
) : ViewModel() {

    private val sectionCoordinator = DiscoverSectionCoordinator(
        recentFavoriteAlbumsTitle = RECENT_FAVORITE_ALBUMS_TITLE,
        recentFavoriteTracksTitle = RECENT_FAVORITE_TRACKS_TITLE
    )
    private val contentLoader = DiscoverContentLoader(musicRepository, playHistoryRepository)
    private val recommendationOrchestrator = DiscoverRecommendationOrchestrator(
        musicRepository = musicRepository,
        playHistoryRepository = playHistoryRepository,
        recommendationEngine = recommendationEngine,
        lastFmSimilarResolver = lastFmSimilarResolver
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
    private var cacheStale = true
    private var mediaEventJob: Job? = null
    private var fullLoadJob: Job? = null
    private var radioStartJob: Job? = null
    private var connectionProbeJob: Job? = null
    private var loadGeneration = 0L
    private var bllArtistScoreMap = emptyMap<String, Double>()
    private var artistDominantDecades = emptyMap<String, Int>()
    private var artistByUri = emptyMap<String, Artist>()
    private var smartArtistScoreMap = emptyMap<String, Double>()
    private var excludedArtistUris = emptySet<String>()
    private var excludedTrackUris = emptySet<String>()
    private var lastRadioStartAtMs = 0L
    private var lastRadioStartGenre: String? = null
    private var lastSmartMixSelection = emptyList<Track>()

    init {
        autoConnect()
        viewModelScope.launch {
            loadFromCache()
            observeConnection()
        }
        viewModelScope.launch {
            observeMediaEvents()
        }
        viewModelScope.launch {
            shortcutDispatcher.pendingAction
                .filterNotNull()
                .filter { it is ShortcutAction.SmartMix }
                .collect {
                    shortcutDispatcher.consume()
                    makePlaylistForMe()
                }
        }
    }

    private fun autoConnect() {
        viewModelScope.launch {
            wsClient.startupReady.first { it }
            if (wsClient.connectionState.value is ConnectionState.Disconnected) {
                val url = settingsRepository.serverUrl.first()
                val token = settingsRepository.authToken.first()
                if (url.isNotBlank() && token.isNotBlank()) {
                    wsClient.connect(url, token)
                }
            }
        }
    }

    private suspend fun loadFromCache() {
        val cached = discoverCache.load() ?: return
        artistByUri = cached.topArtists.mapNotNull { artist ->
            artist.canonicalKey()?.let { it to artist }
        }.toMap()
        val historyGenreArtists = try {
            playHistoryRepository.getGenreArtistMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val (genreItems, builtGenreArtists, builtStrictGenreArtists) =
            contentLoader.buildGenreData(cached.topArtists, historyGenreArtists, artistByUri)
        strictGenreArtists = builtStrictGenreArtists
        genreArtists = builtGenreArtists
        val bllScores = try {
            playHistoryRepository.getScoredGenres(days = 90, limit = 20)
        } catch (_: Exception) {
            emptyList()
        }
        _uiState.value = _uiState.value.copy(
            sections = sectionBuilder.buildSections(
            serverFolders = cached.serverFolders,
            suggestedArtists = cached.suggestedArtists,
            suggestedAlbums = cached.discoverAlbums,
            genreItems = genreItems,
            bllGenreScores = bllScores
            ),
            isLoading = false
        )
        cacheStale = discoverCache.isStale(cached)
    }

    private suspend fun observeConnection() {
        wsClient.connectionState.collect { state ->
            if (state is ConnectionState.Connected) {
                loadFromServer()
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
                val hadDstm = playerRepository.queueState.value?.dontStopTheMusicEnabled ?: false
                if (hadDstm) {
                    musicRepository.setDontStopTheMusic(queueId, false)
                }
                playerRepository.setQueueFilterMode(
                    queueId,
                    PlayerRepository.QueueFilterMode.SMART_GENERATED
                )
                lastSmartMixSelection = mixResult.tracks
                musicRepository.playMedia(queueId, trackUris, option = "replace")
                if (hadDstm) {
                    musicRepository.setDontStopTheMusic(queueId, true)
                }
                logQueueContents("smartMix", awaitQueueItems = true)
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
    }

    private suspend fun buildSmartMixTracks(): SmartMixResult {
        val mixSeed = System.currentTimeMillis()
        val random = kotlin.random.Random(mixSeed)
        val artistScores = try {
            playHistoryRepository.getScoredArtists(days = 120, limit = 40)
        } catch (_: Exception) {
            emptyList()
        }
        val genreScores = try {
            playHistoryRepository.getScoredGenres(days = 120, limit = 10)
        } catch (_: Exception) {
            emptyList()
        }
        val recentArtistScores = try {
            playHistoryRepository.getScoredArtists(days = SMART_MIX_RECENT_LOOKBACK_DAYS, limit = 24)
        } catch (_: Exception) {
            emptyList()
        }
        val recentGenreScores = try {
            playHistoryRepository.getScoredGenres(days = SMART_MIX_RECENT_LOOKBACK_DAYS, limit = 8)
        } catch (_: Exception) {
            emptyList()
        }
        if (artistScores.isEmpty() && genreScores.isEmpty()) return SmartMixResult(emptyList(), null)
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

        // Try up to 3 genres, pick via weighted random, fallback if too few tracks
        val triedGenres = mutableSetOf<String>()
        for (candidate in timeAwareGenreScores.take(3)) {
            val pickedGenre = if (triedGenres.isEmpty()) {
                weightedRandomGenre(
                    timeAwareGenreScores.filter { it.genre !in triedGenres },
                    random
                ) ?: continue
            } else {
                // Second/third attempt: pick next best untried genre deterministically
                candidate.genre.takeIf { it !in triedGenres } ?: continue
            }
            triedGenres.add(pickedGenre)

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
                mixSeed = mixSeed
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
            bllArtistScoreMap = bllArtistScoreMap,
            smartArtistScoreMap = smartArtistScoreMap,
            favoriteArtistUris = favoriteArtistUris,
            excludedArtistUris = excludedArtistUris,
            randomSeed = mixSeed
        )
        if (artistOrder.isEmpty()) return SmartMixResult(emptyList(), null)

        val tracksByArtist = mutableMapOf<String, List<Track>>()
        for (artistUri in artistOrder) {
            val tracks = getArtistTracksForIdentity(artistUri)
            if (tracks.isNotEmpty()) tracksByArtist[artistUri] = tracks
        }
        if (tracksByArtist.isEmpty()) return SmartMixResult(emptyList(), null)

        return SmartMixResult(
            mixEngine.buildTracks(
                mode = smartMixMode,
                artistOrder = artistOrder,
                tracksByArtist = tracksByArtist,
                excludedArtistUris = excludedArtistUris,
                excludedTrackUris = excludedTrackUris,
                favoriteArtistUris = favoriteArtistUris,
                favoriteAlbumUris = favoriteAlbumUris,
                artistBaseScore = { uri ->
                    val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                    (artistScoreMap[key] ?: bllArtistScoreMap[key] ?: 0.0) +
                        (recentArtistScoreMap[key] ?: 0.0) * 0.75 +
                        (smartArtistScoreMap[key] ?: 0.0) * 0.5 +
                        daypartTrackBias(daypartAffinity[key])
                },
                target = SMART_MIX_TRACK_TARGET,
                randomSeed = mixSeed + 17L
            ),
            null
        )
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
        mixSeed: Long
    ): List<Track> {
        val genreArtistKeys = (mixGenreArtists[pickedGenre] ?: emptyList()).toSet()
        val filteredGenreArtists = mapOf(pickedGenre to genreArtistKeys.toList())
        val filteredGenreScores = timeAwareGenreScores.filter { normalizeGenre(it.genre) == pickedGenre }
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
            bllArtistScoreMap = bllArtistScoreMap,
            smartArtistScoreMap = smartArtistScoreMap,
            favoriteArtistUris = genreFavorites.toSet(),
            excludedArtistUris = excludedArtistUris,
            randomSeed = mixSeed
        )
        if (artistOrder.isEmpty()) return emptyList()

        val tracksByArtist = mutableMapOf<String, List<Track>>()
        for (artistUri in artistOrder) {
            val tracks = getArtistTracksForIdentity(artistUri)
            if (tracks.isNotEmpty()) tracksByArtist[artistUri] = tracks
        }
        if (tracksByArtist.isEmpty()) return emptyList()

        return mixEngine.buildTracks(
            mode = smartMixMode,
            artistOrder = artistOrder,
            tracksByArtist = tracksByArtist,
            excludedArtistUris = excludedArtistUris,
            excludedTrackUris = excludedTrackUris,
            favoriteArtistUris = genreFavorites.toSet(),
            favoriteAlbumUris = favoriteAlbumUris,
            artistBaseScore = { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                (artistScoreMap[key] ?: bllArtistScoreMap[key] ?: 0.0) +
                    (recentArtistScoreMap[key] ?: 0.0) * 0.75 +
                    (smartArtistScoreMap[key] ?: 0.0) * 0.5 +
                    daypartTrackBias(daypartAffinity[key])
            },
            target = SMART_MIX_TRACK_TARGET,
            randomSeed = mixSeed + 17L
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
    private fun loadFromServer(isManualRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                val beta = settingsRepository.includeBetaUpdates.first()
                appUpdateChecker.checkForUpdates(force = false, includePrerelease = beta)
            } catch (_: Exception) { /* best-effort */ }
        }
        if (!isManualRefresh && !cacheStale && _uiState.value.sections.isNotEmpty()) {
            Log.d(TAG, "loadFromServer: skipped (cache fresh)")
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

                val suggested = try {
                    recommendationOrchestrator.buildSuggestedArtists(
                        candidateArtists = smartFilteredArtists,
                        excludedArtistUris = excludedArtistUris,
                        artistSignalScores = smartArtistScoreMap,
                        artistIdentity = { artist -> artist.canonicalKey() ?: artist.uri }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to build suggested artists", e)
                    smartFilteredArtists.shuffled().take(10)
                }
                Log.d(TAG, "Suggested artists: ${suggested.size}")

                val discover = recommendationOrchestrator.loadDiscoverAlbums(
                    candidateArtists = smartFilteredArtists,
                    recommendationAlbums = content.recommendationAlbums,
                    excludedArtistUris = excludedArtistUris,
                    artistSignalScores = smartArtistScoreMap,
                    albumIdentity = { album -> album.canonicalKey() ?: album.uri },
                    artistIdentity = { artist -> artist.canonicalKey() ?: artist.uri },
                    loadArtistAlbumsForIdentity = ::getArtistAlbumsForIdentity
                )

                val bllGenreScores = try {
                    playHistoryRepository.getScoredGenres(days = 90, limit = 20)
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
                discoverCache.save(
                    DiscoverCache.CacheData(
                        suggestedArtists = suggested,
                        discoverAlbums = discover ?: emptyList(),
                        topArtists = merged,
                        serverFolders = content.enrichedFolders
                    )
                )
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
        val candidateUris = when {
            strictCandidates.size >= 4 -> strictCandidates
            strictCandidates.isNotEmpty() -> (strictCandidates + broadCandidates)
                .distinctBy { uri -> MediaIdentity.artistKeyFromUri(uri) ?: uri }
            else -> broadCandidates
        }
        if (candidateUris.isEmpty()) return
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
                val usedLocalMix = startLocalGenreMix(
                    queueId = queueId,
                    genre = genre,
                    candidateUris = candidateUris
                )
                if (!usedLocalMix) {
                    val focusDecade = loadGenreRadioFocusDecade(genre, candidateUris)
                    val rankedUris = rankGenreRadioArtistUris(candidateUris, focusDecade)
                    val libraryUris = prioritizeDecadeCoherentUris(rankedUris, focusDecade)
                        .take(MAX_GENRE_RADIO_ARTIST_URIS)
                    if (libraryUris.isEmpty()) return@launch

                    val discoveryUris = resolveDiscoverySeeds(libraryUris, genre)
                    val allSeeds = (libraryUris + discoveryUris).distinct()

                    Log.d(
                        TAG,
                        "startGenreRadio fallback: genre='$genre', strict=${strictCandidates.size}, " +
                            "broad=${broadCandidates.size}, decadeFocus=$focusDecade, " +
                            "library=${libraryUris.size}, discovery=${discoveryUris.size}"
                    )
                    allSeeds.forEachIndexed { i, uri ->
                        val key = MediaIdentity.artistKeyFromUri(uri)
                        val name = artistByUri[key]?.name ?: artistByUri[uri]?.name ?: uri
                        val bll = bllArtistScoreMap[key ?: uri]?.let { String.format("%.2f", it) } ?: "-"
                        val decade = artistDominantDecades[key ?: uri]?.toString() ?: "-"
                        val tag = if (uri in discoveryUris) " [DISCOVERY]" else ""
                        Log.d(TAG, "  fallback seed #${i + 1}: $name (bll=$bll, decade=$decade)$tag $uri")
                    }
                    val requestAccepted = startGenreRadioWithFallback(
                        queueId = queueId,
                        genre = genre,
                        rankedUris = allSeeds
                    )
                    if (!requestAccepted) {
                        Log.w(TAG, "startGenreRadio: all seed attempts failed for genre='$genre'")
                        return@launch
                    }
                }
                val started = waitForGenreRadioStart(
                    wasPlayingBefore = wasPlayingBefore,
                    baselineTrackUri = baselineTrackUri
                )
                if (!started) {
                    Log.w(TAG, "startGenreRadio: start confirmation timeout for genre='$genre'")
                }
                if (usedLocalMix) {
                    logQueueContents("genreMix[$genre]", awaitQueueItems = true)
                } else {
                    sanitizeGenreRadioQueue(queueId, genre)
                    logQueueContents("genreRadio[fallback][$genre]", awaitQueueItems = true)
                }
                _uiState.value = _uiState.value.copy(radioOverlayGenre = null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start genre radio", e)
                _uiState.value = _uiState.value.copy(radioOverlayGenre = null)
            } finally {
                radioStartJob = null
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveDiscoveryArtistsWithTracks(
        libraryArtistUris: List<String>,
        genre: String
    ): Map<String, List<Track>> {
        val seedArtists = libraryArtistUris.take(GENRE_RADIO_SIMILAR_RESOLVE_LIMIT).mapNotNull { uri ->
            val key = MediaIdentity.artistKeyFromUri(uri)
            artistByUri[key]?.name ?: artistByUri[uri]?.name
        }
        if (seedArtists.isEmpty()) return emptyMap()

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
        if (discoveryNames.isEmpty()) return emptyMap()

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
            "resolveDiscoveryArtists: genre-validated ${validatedNames.size}/${discoveryNames.size} " +
                "for '$genre'"
        )
        if (validatedNames.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, List<Track>>()
        for (name in validatedNames.take(GENRE_RADIO_DISCOVERY_SEEDS * 2)) {
            if (result.size >= GENRE_RADIO_DISCOVERY_SEEDS) break
            try {
                val searchResult = musicRepository.search(name, mediaTypes = listOf(MediaType.ARTIST), limit = 1)
                val artist = searchResult.artists.firstOrNull() ?: continue
                if (artist.name.lowercase() != name) continue
                val tracks = musicRepository.getArtistTracks(artist.itemId, artist.provider)
                if (tracks.isNotEmpty()) {
                    result[artist.uri] = tracks
                    Log.d(TAG, "Discovery artist: $name -> ${artist.uri} (${tracks.size} tracks)")
                }
            } catch (_: Exception) {
                // skip
            }
        }
        Log.d(TAG, "resolveDiscoveryArtists: ${result.size} with tracks from ${validatedNames.size} validated")
        return result
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startLocalGenreMix(
        queueId: String,
        genre: String,
        candidateUris: List<String>
    ): Boolean {
        val artistCandidates = candidateUris.take(MAX_GENRE_RADIO_ARTIST_URIS)
        if (artistCandidates.isEmpty()) return false

        val favoriteArtistUris = contentLoader.loadFavoriteArtistKeys(SMART_MIX_FAVORITES_QUERY_LIMIT)
        val favoriteAlbumUris = contentLoader.loadFavoriteAlbumKeys(SMART_MIX_FAVORITES_QUERY_LIMIT)
        val recentArtistScoreMap = try {
            playHistoryRepository.getScoredArtists(
                days = SMART_MIX_RECENT_LOOKBACK_DAYS, limit = 24
            ).toScoreMap()
        } catch (_: Exception) { emptyMap() }
        val daypartAffinity = try {
            playHistoryRepository.getArtistDaypartAffinity(
                targetHour = LocalTime.now().hour,
                days = SMART_MIX_DAYPART_LOOKBACK_DAYS
            )
        } catch (_: Exception) { emptyMap() }
        val genreMixMode = MixMode.GenreMix(
            genre = genre,
            artistUris = artistCandidates,
            recentArtistScoreMap = recentArtistScoreMap,
            daypartAffinityByArtist = daypartAffinity
        )
        val rankedArtists = mixEngine.buildArtistOrder(
            mode = genreMixMode,
            bllArtistScoreMap = bllArtistScoreMap,
            smartArtistScoreMap = smartArtistScoreMap,
            favoriteArtistUris = favoriteArtistUris,
            excludedArtistUris = excludedArtistUris,
            randomSeed = System.currentTimeMillis()
        ).take(GENRE_MIX_ARTIST_LIMIT)

        val tracksByArtist = linkedMapOf<String, List<Track>>()
        for (artistUri in rankedArtists) {
            val tracks = getArtistTracksForIdentity(artistUri)
            if (tracks.isNotEmpty()) {
                tracksByArtist[artistUri] = tracks
            }
        }

        val discoveryArtists = resolveDiscoveryArtistsWithTracks(rankedArtists, genre)
        val allArtistOrder = rankedArtists.toMutableList()
        for ((uri, tracks) in discoveryArtists) {
            tracksByArtist[uri] = tracks
            allArtistOrder.add(uri)
        }

        val trackUris = mixEngine.buildTrackUris(
            mode = genreMixMode,
            artistOrder = allArtistOrder,
            tracksByArtist = tracksByArtist,
            excludedArtistUris = excludedArtistUris,
            excludedTrackUris = excludedTrackUris,
            favoriteArtistUris = favoriteArtistUris,
            favoriteAlbumUris = favoriteAlbumUris,
            artistBaseScore = { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri) ?: uri
                (bllArtistScoreMap[key] ?: 0.0) +
                    (recentArtistScoreMap[key] ?: 0.0) * 0.75 +
                    (smartArtistScoreMap[key] ?: 0.0) * 0.5 +
                    daypartTrackBias(daypartAffinity[key])
            },
            target = GENRE_MIX_TRACK_TARGET,
            randomSeed = System.currentTimeMillis() + 37L
        )

        if (trackUris.size < GENRE_MIX_MIN_QUEUE_SIZE) {
            Log.w(TAG, "startLocalGenreMix: insufficient queue for genre='$genre'")
            return false
        }

        Log.d(
            TAG,
            "startLocalGenreMix: genre='$genre', artists=${rankedArtists.size}, " +
                "trackPools=${tracksByArtist.size}, built=${trackUris.size}"
        )

        val hadDstm = playerRepository.queueState.value?.dontStopTheMusicEnabled ?: false
        if (hadDstm) {
            musicRepository.setDontStopTheMusic(queueId, false)
        }
        playerRepository.setQueueFilterMode(
            queueId,
            PlayerRepository.QueueFilterMode.RADIO_SMART
        )
        musicRepository.playMedia(queueId, trackUris, option = "replace")
        if (hadDstm) {
            musicRepository.setDontStopTheMusic(queueId, true)
        }
        musicRepository.playQueueIndex(queueId, 0)
        return true
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
            playHistoryRepository.getTopDecadesForGenre(
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

                val artistName = track?.artistNames?.split(",")?.firstOrNull()?.trim()
                val artistGenres = artistName?.let { artistGenreMap[it.lowercase()] }
                val offGenre = !artistGenres.isNullOrEmpty() &&
                    artistGenres.none { isGenreRelated(it, genre) }

                if (duplicateTrack || consecutiveSameArtist || offGenre) {
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
            val name = item.track?.artistNames?.split(",")?.firstOrNull()?.trim() ?: continue
            val genres = item.track.genres
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

    private fun isGenreRelated(artistGenre: String, targetGenre: String): Boolean =
        artistGenre == targetGenre ||
            artistGenre.contains(targetGenre) ||
            targetGenre.contains(artistGenre)

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

    private suspend fun getArtistTracksForIdentity(artistIdentity: String): List<Track> {
        val cacheKey = MediaIdentity.artistKeyFromUri(artistIdentity) ?: artistIdentity
        playHistoryRepository.getCachedArtistTracks(cacheKey, ARTIST_TRACK_CACHE_TTL_MS)?.let { cached ->
            if (cached.isNotEmpty()) {
                Log.d(TAG, "Artist track cache hit: $cacheKey (${cached.size} tracks)")
                return enrichTracksWithLastFmGenres(cached)
            }
        }
        for ((provider, itemId) in resolveArtistRefs(artistIdentity)) {
            try {
                val tracks = musicRepository.getArtistTracks(itemId, provider)
                if (tracks.isNotEmpty()) {
                    val enriched = enrichTracksWithLastFmGenres(tracks)
                    playHistoryRepository.cacheArtistTracks(cacheKey, enriched)
                    Log.d(TAG, "Artist track cache fill: $cacheKey (${enriched.size} tracks)")
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
        if (tracks.any { it.genres.isNotEmpty() }) return tracks
        val artistName = tracks.first().artistNames.split(",").firstOrNull()?.trim()
        if (artistName.isNullOrBlank()) return tracks
        val genres = try {
            lastFmGenreResolver.resolve(artistName)
        } catch (_: Exception) {
            emptyList()
        }
        if (genres.isEmpty()) return tracks
        return tracks.map { it.copy(genres = genres) }
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
