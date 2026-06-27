package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverContentLoader
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSectionBuilder
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.MixPlaybackOrchestrator
import net.asksakis.massdroidv2.domain.recommendation.canonicalKey
import net.asksakis.massdroidv2.domain.recommendation.RecommendationEngine
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import net.asksakis.massdroidv2.domain.shortcut.ShortcutAction
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher
import javax.inject.Inject

private const val TAG = "DiscoverVM"
private const val RECENT_FAVORITE_ALBUMS_TITLE = "Recent Favorite Albums"
private const val RECENT_FAVORITE_TRACKS_TITLE = "Recent Favorite Tracks"
private const val RECENT_TRACKS_QUERY_FACTOR = 5
// Genre Radio UI debounce: ignore a repeat tap on the same genre within this window.
private const val GENRE_RADIO_SPAM_WINDOW_MS = 1_500L
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
    private val mixOrchestrator: MixPlaybackOrchestrator,
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
    private var artistByUri = emptyMap<String, Artist>()
    private var excludedArtistUris = emptySet<String>()
    private var excludedTrackUris = emptySet<String>()
    // Genre Radio UI debounce (the build itself lives in MixPlaybackOrchestrator).
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
        artistByUri = emptyMap()
        excludedArtistUris = emptySet()
        excludedTrackUris = emptySet()
        lastRadioStartAtMs = 0L
        lastRadioStartGenre = null
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

    // Smart Mix build+play is owned by the shared MixPlaybackOrchestrator (so the
    // headless AAOS car can run the identical mix). This keeps only the UI seams:
    // the selected-player check, the building flag, and mapping the result to a
    // user message. The already-loaded library maps are handed in so the phone
    // never re-loads (the car path self-loads them inside the orchestrator).
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
                val result = mixOrchestrator.playSmartMix(queueId, currentLibraryContext())
                smartMixMessageFor(result)?.let { message ->
                    _uiState.value = _uiState.value.copy(smartMixMessage = message)
                }
            } finally {
                _uiState.value = _uiState.value.copy(isBuildingSmartMix = false)
            }
        }
    }

    private fun currentLibraryContext() = MixPlaybackOrchestrator.LibraryContext(
        artistByUri = artistByUri,
        genreArtists = genreArtists,
        strictGenreArtists = strictGenreArtists
    )

    private fun smartMixMessageFor(result: MixPlaybackOrchestrator.MixResult): String? = when (result) {
        is MixPlaybackOrchestrator.MixResult.Played -> result.genre?.let { genre ->
            "${genre.replaceFirstChar { it.uppercase() }} mix ready (${result.count} tracks)"
        } ?: "Smart mix ready (${result.count} tracks)"
        MixPlaybackOrchestrator.MixResult.NotEnoughData -> "Not enough listening data for a solid mix yet"
        MixPlaybackOrchestrator.MixResult.NoPlayer -> "Select a player first"
        is MixPlaybackOrchestrator.MixResult.Failed -> result.message
        // Busy / radio-only outcomes carry no Smart Mix message.
        else -> null
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
                    excludedArtistUris = blocked + suppressed
                    excludedTrackUris = smartListeningRepository.getSuppressedTrackUris()
                } else {
                    excludedArtistUris = emptySet()
                    excludedTrackUris = emptySet()
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
                if (generation != loadGeneration) return@launch

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

    // Genre Radio build+play is owned by the shared MixPlaybackOrchestrator (so the
    // headless AAOS car runs the identical radio). This keeps only the UI seams:
    // the selected-player check, the spam / in-flight debounce, the radio overlay,
    // and mapping the result to a user message. A cheap pre-check on the raw genre
    // pools avoids flashing the overlay when the genre has no artists at all.
    fun startGenreRadio(genre: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        val nowMs = System.currentTimeMillis()
        if (genreArtists[genre].isNullOrEmpty() && strictGenreArtists[genre].isNullOrEmpty()) {
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

        radioStartJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(radioOverlayGenre = genre)
                val result = mixOrchestrator.playGenreRadio(queueId, genre, currentLibraryContext())
                _uiState.value = _uiState.value.copy(
                    radioOverlayGenre = null,
                    smartMixMessage = genreRadioMessageFor(result, genre)
                )
            } finally {
                radioStartJob = null
            }
        }
    }

    private fun genreRadioMessageFor(
        result: MixPlaybackOrchestrator.MixResult,
        genre: String
    ): String? = when (result) {
        is MixPlaybackOrchestrator.MixResult.Played ->
            "${genre.replaceFirstChar { it.uppercase() }} radio ready (${result.count} tracks)"
        MixPlaybackOrchestrator.MixResult.NoMatchingArtists ->
            "Not enough matching artists to start $genre radio"
        MixPlaybackOrchestrator.MixResult.StartTimeout -> "$genre radio did not start in time"
        is MixPlaybackOrchestrator.MixResult.Failed -> "Failed to start $genre radio"
        // Started (server radio) / Busy / others: clear the overlay with no message.
        else -> null
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

    private fun isArtistExcluded(artist: Artist, excludedKeys: Set<String>): Boolean {
        val key = artist.canonicalKey()
        return key != null && key in excludedKeys
    }
}
