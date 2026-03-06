package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSectionBuilder
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.RecommendationEngine
import net.asksakis.massdroidv2.domain.recommendation.SmartMixEngine
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
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
private const val GENRE_RADIO_ATTEMPT_POOL_SIZE = 12
private val GENRE_RADIO_BATCH_SIZES = listOf(8, 4, 2, 1)
private const val GENRE_RADIO_EXPLORATION_COUNT = 4
private const val GENRE_RADIO_ALLOWED_DECADE_GAP = 10
private const val GENRE_RADIO_DECADE_LOOKBACK_DAYS = 720
private const val ARTIST_DECADE_LOOKBACK_DAYS = 720
private const val SMART_MIX_DAYPART_LOOKBACK_DAYS = 180
private const val SMART_MIX_TRACK_TARGET = 40
private const val SMART_MIX_FAVORITES_QUERY_LIMIT = 500
private const val GENRE_RADIO_SPAM_WINDOW_MS = 1_500L
private const val GENRE_RADIO_START_WAIT_TIMEOUT_MS = 8_000L
private const val CONNECTION_PING_SAMPLES = 3
private const val CONNECTION_PING_TIMEOUT_MS = 3_000L

data class ConnectionProbeState(
    val inProgress: Boolean = false,
    val samplesMs: List<Long> = emptyList(),
    val failedSamples: Int = 0,
    val probeMethod: String? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0L
)

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
    private val smartMixEngine: SmartMixEngine,
    private val sectionBuilder: DiscoverSectionBuilder
) : ViewModel() {

    private val sectionCoordinator = DiscoverSectionCoordinator(
        recentFavoriteAlbumsTitle = RECENT_FAVORITE_ALBUMS_TITLE,
        recentFavoriteTracksTitle = RECENT_FAVORITE_TRACKS_TITLE
    )
    private val contentLoader = DiscoverContentLoader(musicRepository, playHistoryRepository)
    private val recommendationOrchestrator = DiscoverRecommendationOrchestrator(
        musicRepository = musicRepository,
        playHistoryRepository = playHistoryRepository,
        recommendationEngine = recommendationEngine
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
    private var lastRadioStartAtMs = 0L
    private var lastRadioStartGenre: String? = null

    init {
        autoConnect()
        viewModelScope.launch {
            loadFromCache()
            observeConnection()
        }
        viewModelScope.launch {
            observeMediaEvents()
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
            artistKey(artist)?.let { it to artist }
        }.toMap()
        val historyGenreArtists = try {
            playHistoryRepository.getGenreArtistMap()
        } catch (_: Exception) {
            emptyMap()
        }
        genreArtists = buildMap {
            val allGenres = cached.topArtists.flatMap { it.genres }.toSet() + historyGenreArtists.keys
            val cachedArtistMap = cached.topArtists.mapNotNull { artist ->
                artistKey(artist)?.let { it to artist }
            }.toMap()
            for (genre in allGenres) {
                val merged = buildList {
                    addAll(cached.topArtists.filter { genre in it.genres }.map { it.uri })
                    addAll(
                        historyGenreArtists[genre]
                            .orEmpty()
                            .mapNotNull { key -> cachedArtistMap[key]?.uri }
                    )
                }.distinctBy { artistKeyFromUri(it) ?: it }
                put(genre, merged)
            }
        }
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
            genreItems = buildGenreItemsFallback(cached.topArtists, historyGenreArtists),
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
                val trackUris = buildSmartMixTrackUris()
                if (trackUris.size < 8) {
                    _uiState.value = _uiState.value.copy(
                        smartMixMessage = "Not enough listening data for a solid mix yet"
                    )
                    return@launch
                }
                musicRepository.playMedia(queueId, trackUris, option = "replace")
                _uiState.value = _uiState.value.copy(
                    smartMixMessage = "Smart mix ready (${trackUris.size} tracks)"
                )
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

    fun probeConnection(sampleCount: Int = CONNECTION_PING_SAMPLES) {
        connectionProbeJob?.cancel()
        connectionProbeJob = viewModelScope.launch {
            val currentState = wsClient.connectionState.value
            if (currentState !is ConnectionState.Connected) {
                _uiState.value = _uiState.value.copy(
                    connectionProbe = ConnectionProbeState(
                    inProgress = false,
                    samplesMs = emptyList(),
                    failedSamples = 0,
                    error = "Not connected",
                    updatedAtMs = System.currentTimeMillis()
                )
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                connectionProbe = ConnectionProbeState(inProgress = true)
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

            _uiState.value = _uiState.value.copy(
                connectionProbe = ConnectionProbeState(
                inProgress = false,
                samplesMs = samples,
                failedSamples = failed,
                probeMethod = probeMethod,
                error = if (samples.isEmpty()) "Ping failed" else null,
                updatedAtMs = System.currentTimeMillis()
            )
            )
        }
    }

    private suspend fun refreshSmartFiltersForMix() {
        val smartListeningEnabled = settingsRepository.smartListeningEnabled.first()
        if (smartListeningEnabled) {
            val blocked = smartListeningRepository.getBlockedArtistUris()
            val suppressed = smartListeningRepository.getSuppressedArtistUris(days = 120)
            val metrics = smartListeningRepository.getArtistMetrics(days = 120)
            excludedArtistUris = blocked + suppressed
            smartArtistScoreMap = metrics.mapValues { it.value.score }
        } else {
            excludedArtistUris = emptySet()
            smartArtistScoreMap = emptyMap()
        }
    }

    private suspend fun buildSmartMixTrackUris(): List<String> {
        val mixSeed = System.currentTimeMillis()
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
        if (artistScores.isEmpty() && genreScores.isEmpty()) return emptyList()
        val artistScoreMap = artistScores.associate { it.artistUri to it.score }
        val daypartAffinity = try {
            playHistoryRepository.getArtistDaypartAffinity(
                targetHour = LocalTime.now().hour,
                days = SMART_MIX_DAYPART_LOOKBACK_DAYS
            )
        } catch (_: Exception) {
            emptyMap()
        }
        val favoriteArtistUris = loadSmartMixFavoriteArtistUris()
        val favoriteAlbumUris = loadSmartMixFavoriteAlbumUris()

        val mixGenreArtists = genreArtists.mapValues { (_, uris) ->
            uris.mapNotNull { artistKeyFromUri(it) }.distinct()
        }
        val artistOrder = smartMixEngine.buildArtistOrder(
            artistScores = artistScores,
            genreScores = genreScores,
            genreArtists = mixGenreArtists,
            excludedArtistUris = excludedArtistUris,
            favoriteArtistUris = favoriteArtistUris,
            bllArtistScoreMap = bllArtistScoreMap,
            smartArtistScoreMap = smartArtistScoreMap,
            daypartAffinityByArtist = daypartAffinity,
            randomSeed = mixSeed
        )
        if (artistOrder.isEmpty()) return emptyList()

        val tracksByArtist = mutableMapOf<String, List<Track>>()
        for (artistUri in artistOrder) {
            val tracks = getArtistTracksForIdentity(artistUri)
            if (tracks.isNotEmpty()) tracksByArtist[artistUri] = tracks
        }
        if (tracksByArtist.isEmpty()) return emptyList()

        return smartMixEngine.buildTrackUris(
            artistOrder = artistOrder,
            tracksByArtist = tracksByArtist,
            genreScores = genreScores,
            excludedArtistUris = excludedArtistUris,
            favoriteAlbumUris = favoriteAlbumUris,
            artistBaseScore = { uri ->
                val key = artistKeyFromUri(uri) ?: uri
                (artistScoreMap[key] ?: bllArtistScoreMap[key] ?: 0.0) +
                    (smartArtistScoreMap[key] ?: 0.0) * 0.5 +
                    daypartTrackBias(daypartAffinity[key])
            },
            target = SMART_MIX_TRACK_TARGET,
            randomSeed = mixSeed + 17L
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadSmartMixFavoriteArtistUris(): Set<String> = try {
        musicRepository.getArtists(
            orderBy = "play_count_desc",
            limit = SMART_MIX_FAVORITES_QUERY_LIMIT,
            favoriteOnly = true
        )
            .asSequence()
            .filter { it.favorite }
            .mapNotNull { artistKey(it) }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadSmartMixFavoriteAlbumUris(): Set<String> = try {
        musicRepository.getAlbums(
            orderBy = "play_count_desc",
            limit = SMART_MIX_FAVORITES_QUERY_LIMIT,
            favoriteOnly = true
        )
            .asSequence()
            .filter { it.favorite }
            .mapNotNull { albumKey(it) }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun daypartTrackBias(affinity: Double?): Double {
        if (affinity == null) return 0.0
        return ((affinity - 0.45) * 0.45).coerceIn(-0.15, 0.20)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadFromServer(isManualRefresh: Boolean = false) {
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
                    artistKey(artist)?.let { it to artist }
                }.toMap()

                val smartListeningEnabled = settingsRepository.smartListeningEnabled.first()
                if (smartListeningEnabled) {
                    val blocked = smartListeningRepository.getBlockedArtistUris()
                    val suppressed = smartListeningRepository.getSuppressedArtistUris(days = 120)
                    val metrics = smartListeningRepository.getArtistMetrics(days = 120)
                    excludedArtistUris = blocked + suppressed
                    smartArtistScoreMap = metrics.mapValues { it.value.score }
                } else {
                    excludedArtistUris = emptySet()
                    smartArtistScoreMap = emptyMap()
                }
                val smartFilteredArtists = if (excludedArtistUris.isEmpty()) {
                    merged
                } else {
                    merged.filterNot { isArtistExcluded(it, excludedArtistUris) }
                }
                genreArtists = content.genreArtists
                    .mapValues { (_, uris) ->
                        uris.filterNot { uri ->
                            val key = artistKeyFromUri(uri)
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
                        artistIdentity = { artist -> artistKey(artist) ?: artist.uri }
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
                    albumIdentity = { album -> albumKey(album) ?: album.uri },
                    artistIdentity = { artist -> artistKey(artist) ?: artist.uri },
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
                bllArtistScoreMap = bllArtistScores.associate { it.artistUri to it.score }

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
        val genreMap = mutableMapOf<String, MutableList<Artist>>()
        for (artist in artists) {
            for (genre in artist.genres) {
                genreMap.getOrPut(genre) { mutableListOf() }.add(artist)
            }
        }
        // Merge server artists with Room DB artists (distinct URIs per genre)
        val serverUris = genreMap.mapValues { (_, artistList) ->
            artistList.map { it.uri }
        }
        genreArtists = buildMap {
            val allGenres = serverUris.keys + historyGenreArtists.keys
            for (genre in allGenres) {
                val merged = buildList {
                    addAll(serverUris[genre].orEmpty())
                    addAll(
                        historyGenreArtists[genre]
                            .orEmpty()
                            .mapNotNull { key -> artistByUri[key]?.uri }
                    )
                }.distinctBy { artistKeyFromUri(it) ?: it }
                put(genre, merged)
            }
        }
        for ((genre, uris) in genreArtists.entries.sortedByDescending { it.value.size }.take(10)) {
            val serverCount = serverUris[genre]?.size ?: 0
            val historyCount = historyGenreArtists[genre]?.size ?: 0
            Log.d(TAG, "Genre '$genre': ${uris.size} artists (server=$serverCount, history=$historyCount)")
        }
        return genreMap
            .map { (name, artistList) ->
                GenreItem(
                    name = name,
                    count = artistList.size,
                    imageUrl = artistList.firstOrNull()?.imageUrl
                )
            }
            .sortedByDescending { it.count }
            .take(10)
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
            .distinctBy { trackKey(it) ?: it.uri }
            .take(limit)
    }

    fun startGenreRadio(genre: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        val nowMs = System.currentTimeMillis()
        val candidateUris = genreArtists[genre]
            ?.distinctBy { uri -> artistKeyFromUri(uri) ?: uri }
            ?.filterNot { uri ->
                val key = artistKeyFromUri(uri)
                key != null && key in excludedArtistUris
            }
            .orEmpty()
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
                ensureBllArtistScoresLoaded()
                ensureArtistDecadesLoaded()
                val focusDecade = loadGenreRadioFocusDecade(genre, candidateUris)
                val rankedUris = rankGenreRadioArtistUris(candidateUris, focusDecade)
                val payloadUris = prioritizeDecadeCoherentUris(rankedUris, focusDecade)
                    .take(MAX_GENRE_RADIO_ARTIST_URIS)
                if (payloadUris.isEmpty()) return@launch

                Log.d(
                    TAG,
                    "startGenreRadio: genre='$genre', decadeFocus=$focusDecade, " +
                        "sending ${payloadUris.size}/${candidateUris.size} artist URIs"
                )
                _uiState.value = _uiState.value.copy(radioOverlayGenre = genre)
                val requestAccepted = startGenreRadioWithFallback(
                    queueId = queueId,
                    genre = genre,
                    rankedUris = payloadUris
                )
                if (!requestAccepted) {
                    Log.w(TAG, "startGenreRadio: all seed attempts failed for genre='$genre'")
                    return@launch
                }
                val started = waitForGenreRadioStart(
                    wasPlayingBefore = wasPlayingBefore,
                    baselineTrackUri = baselineTrackUri
                )
                if (!started) {
                    Log.w(TAG, "startGenreRadio: start confirmation timeout for genre='$genre'")
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
                    musicRepository.playMedia(
                        queueId = queueId,
                        uris = batch,
                        radioMode = true,
                        awaitResponse = true
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
                .associate { it.artistUri to it.score }
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
            val key = artistKeyFromUri(uri) ?: return@mapNotNull null
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
        val unique = candidateUris.distinctBy { uri -> artistKeyFromUri(uri) ?: uri }
        if (unique.isEmpty()) return emptyList()
        val hasScores = bllArtistScoreMap.isNotEmpty()
        val ranked = unique.shuffled().sortedByDescending { uri ->
            val key = artistKeyFromUri(uri) ?: uri
            val bll = bllArtistScoreMap[key] ?: if (hasScores) Double.NEGATIVE_INFINITY else 0.0
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
        return (exploit + explore + orderedTail).distinctBy { uri -> artistKeyFromUri(uri) ?: uri }
    }

    private fun prioritizeDecadeCoherentUris(rankedUris: List<String>, focusDecade: Int?): List<String> {
        if (focusDecade == null) return rankedUris
        val preferred = rankedUris.filter { uri ->
            val key = artistKeyFromUri(uri) ?: return@filter true
            val decade = artistDominantDecades[key] ?: return@filter true
            abs(decade - focusDecade) <= GENRE_RADIO_ALLOWED_DECADE_GAP
        }
        val fallback = rankedUris.filterNot { it in preferred }
        return preferred + fallback
    }

    private fun decadeAdjustment(uri: String, focusDecade: Int?): Double {
        if (focusDecade == null) return 0.0
        val key = artistKeyFromUri(uri) ?: return 0.0
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
        val key = artistKeyFromUri(uri) ?: return false
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

    fun playTrack(track: Track) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
        for ((provider, itemId) in resolveArtistRefs(artistIdentity)) {
            try {
                val tracks = musicRepository.getArtistTracks(itemId, provider)
                if (tracks.isNotEmpty()) return tracks
            } catch (_: Exception) {
                // Try next candidate ref.
            }
        }
        return emptyList()
    }

    private fun resolveArtistRefs(artistIdentity: String): List<Pair<String, String>> {
        val refs = linkedSetOf<Pair<String, String>>()
        val canonical = artistKeyFromUri(artistIdentity) ?: artistIdentity

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

    private fun artistKey(artist: Artist): String? =
        MediaIdentity.canonicalArtistKey(itemId = artist.itemId, uri = artist.uri)

    private fun artistKeyFromUri(uri: String?): String? =
        MediaIdentity.canonicalArtistKey(uri = uri)

    private fun albumKey(album: Album): String? =
        MediaIdentity.canonicalAlbumKey(itemId = album.itemId, uri = album.uri)

    private fun trackKey(track: Track): String? =
        MediaIdentity.canonicalTrackKey(itemId = track.itemId, uri = track.uri)

    private fun isArtistExcluded(artist: Artist, excludedKeys: Set<String>): Boolean {
        val key = artistKey(artist)
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
