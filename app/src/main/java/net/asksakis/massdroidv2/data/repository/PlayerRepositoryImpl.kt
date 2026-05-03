package net.asksakis.massdroidv2.data.repository

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.asksakis.massdroidv2.data.websocket.*
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlaybackPosition
import net.asksakis.massdroidv2.domain.repository.PlayerDiscontinuityCommand
import net.asksakis.massdroidv2.domain.repository.PlayerSelectionLock
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val json: Json,
    private val playHistoryRepository: PlayHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val lastFmGenreResolver: LastFmGenreResolver,
    private val sessionEventBus: SessionEventBus
) : PlayerRepository {

    companion object {
        private const val TAG = "PlayerRepo"
        private const val BLOCKED_AUTO_SKIP_COOLDOWN_MS = 2_500L
        private const val BLOCKED_QUEUE_CLEANUP_COOLDOWN_MS = 2_000L
        private const val BLOCKED_QUEUE_ITEMS_LIMIT = 500
        private const val HISTORY_GENRE_LOOKUP_TIMEOUT_MS = 1_200L
        private const val HISTORY_ARTIST_LOOKUP_LIMIT = 3
        private const val RECONNECT_QUEUE_REFRESH_ATTEMPTS = 3
        private const val RECONNECT_QUEUE_REFRESH_DELAY_MS = 450L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _players = MutableStateFlow<List<Player>>(emptyList())
    override val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _selectedPlayer = MutableStateFlow<Player?>(null)
    override val selectedPlayer: StateFlow<Player?> = _selectedPlayer.asStateFlow()

    private val _selectionLock = MutableStateFlow<PlayerSelectionLock?>(null)
    override val selectionLock: StateFlow<PlayerSelectionLock?> = _selectionLock.asStateFlow()

    private val _queueState = MutableStateFlow<QueueState?>(null)
    override val queueState: StateFlow<QueueState?> = _queueState.asStateFlow()

    private val _elapsedTime = MutableStateFlow(0.0)
    override val elapsedTime: StateFlow<Double> = _elapsedTime.asStateFlow()

    private val _playbackPosition = MutableStateFlow<PlaybackPosition?>(null)
    override val playbackPosition: StateFlow<PlaybackPosition?> = _playbackPosition.asStateFlow()

    private val _playbackIntent = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    override val playbackIntent: SharedFlow<Boolean> = _playbackIntent.asSharedFlow()

    private val _queueItemsChanged = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val queueItemsChanged: SharedFlow<String> = _queueItemsChanged.asSharedFlow()

    private val _noPlayerSelectedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val noPlayerSelectedEvent: SharedFlow<Unit> = _noPlayerSelectedEvent.asSharedFlow()

    private val _discontinuityCommands = MutableSharedFlow<PlayerDiscontinuityCommand>(extraBufferCapacity = 4)
    override val discontinuityCommands: SharedFlow<PlayerDiscontinuityCommand> = _discontinuityCommands.asSharedFlow()

    override fun requireSelectedPlayerId(): String? {
        val id = selectedPlayer.value?.playerId
        if (id == null) _noPlayerSelectedEvent.tryEmit(Unit)
        return id
    }

    @Volatile private var selectedPlayerId: String? = null

    /**
     * When the selected player is a member of a group (activeGroup != null),
     * the authoritative queue events arrive under the group's ID, not the
     * member's. Resolve to whichever one the server is actually pushing
     * updates for so Now Playing / mini-player keep updating in group mode.
     */
    private fun effectiveQueueId(): String? {
        val id = selectedPlayerId ?: return null
        val player = _players.value.find { it.playerId == id }
        return player?.activeGroup?.takeIf { it.isNotEmpty() } ?: id
    }
    @Volatile private var pendingRestoredPlayerId: String? = null


    // Position tracking for smooth seek bar updates
    @Volatile private var positionBaseTime = 0.0
    @Volatile private var positionBaseTimestamp = 0L
    @Volatile private var isPlaying = false
    private var trackDuration = 0.0
    private var positionTickJob: Job? = null
    private var favoriteOverride: Boolean? = null
    private var favoriteOverrideUri: String? = null

    // Play history tracking (per queue)
    private data class QueueTrackingState(
        val track: Track,
        val artists: List<Pair<String, String>>,
        val startTime: Long
    )
    private data class MaItemRef(
        val itemId: String,
        val provider: String
    )
    private val queueTracking = ConcurrentHashMap<String, QueueTrackingState>()
    private val artistGenreCache = ConcurrentHashMap<String, List<String>>()
    private val libraryArtistUriCache = ConcurrentHashMap<String, String>()
    private val manualSkipByQueue = ConcurrentHashMap<String, String>()
    private val queueReplacementByQueue = ConcurrentHashMap<String, String>()
    private val blockedAutoSkipByQueue = ConcurrentHashMap<String, Pair<String, Long>>()
    private val blockedQueueCleanupAtByQueue = ConcurrentHashMap<String, Long>()
    private val queueFilterModeByQueue = ConcurrentHashMap<String, PlayerRepository.QueueFilterMode>()
    private var blockedQueueCleanupJob: Job? = null
    @Volatile
    private var blockedArtistUrisSnapshot: Set<String> = emptySet()
    @Volatile
    private var suppressedArtistUrisSnapshot: Set<String> = emptySet()
    @Volatile
    private var smartListeningEnabledSnapshot: Boolean = false

    init {
        scope.launch { observeEvents() }
        scope.launch {
            sessionEventBus.resets.collect { resetForAccountSwitch() }
        }
        scope.launch {
            settingsRepository.smartListeningEnabled.collect { enabled ->
                smartListeningEnabledSnapshot = enabled
            }
        }
        scope.launch {
            smartListeningRepository.blockedArtistUris.collect { blocked ->
                blockedArtistUrisSnapshot = blocked
                selectedPlayerId?.let { selectedId ->
                    val currentTrack = queueTracking[selectedId]?.track
                    val artistUris = queueTracking[selectedId]?.artists?.map { it.first }.orEmpty()
                    maybeAutoSkipBlockedTrack(
                        queueId = selectedId,
                        track = currentTrack,
                        artistUris = artistUris,
                        trigger = "blocked_update"
                    )
                    scheduleBlockedQueueCleanup(selectedId, reason = "blocked_update")
                }
            }
        }
        scope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected, is ConnectionState.Connecting -> {
                        // Keep selectedPlayer and queueState for cached mini player display
                        pendingRestoredPlayerId = selectedPlayerId
                        _players.value = emptyList()
                        resetPosition()
                        stopPositionTicker()
                        queueTracking.clear()
                        artistGenreCache.clear()
                        manualSkipByQueue.clear()
                        queueReplacementByQueue.clear()
                        blockedAutoSkipByQueue.clear()
                        blockedQueueCleanupAtByQueue.clear()
                        queueFilterModeByQueue.clear()
                        blockedQueueCleanupJob?.cancel()
                        suppressedArtistUrisSnapshot = emptySet()
                    }
                    is ConnectionState.Connected -> {
                        scope.launch {
                            libraryArtistUriCache.putAll(
                                playHistoryRepository.getLibraryArtistUriMap()
                            )
                        }
                        // Clear stale queue snapshot immediately after reconnect so the UI
                        // falls back to fresh player media instead of showing pre-restart data.
                        _queueState.value = null
                        resetPosition()
                        trackDuration = 0.0
                        favoriteOverride = null
                        favoriteOverrideUri = null
                        stopPositionTicker()
                        val restoredPlayerId = settingsRepository.selectedPlayerId.first()
                        pendingRestoredPlayerId = restoredPlayerId
                        selectedPlayerId = restoredPlayerId
                        refreshPlayers()
                        restoredPlayerId?.let { id ->
                            if (_players.value.any { it.playerId == id }) {
                                selectPlayer(id)
                                refreshQueueForPlayerWithRetry(id)
                                Log.d(TAG, "Restored saved player: $id")
                            } else {
                                Log.d(TAG, "Waiting for late-arriving restored player: $id")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Wipes all per-account state. The default disconnect path intentionally
     * keeps the last selectedPlayer and queue snapshot around so the mini
     * player remains visible during a flap; that is wrong when the account
     * itself changes, because the cached entities belong to a different user.
     */
    private fun resetForAccountSwitch() {
        Log.d(TAG, "Session reset: dropping cached player and queue state")
        blockedQueueCleanupJob?.cancel()
        blockedQueueCleanupJob = null
        stopPositionTicker()
        selectedPlayerId = null
        pendingRestoredPlayerId = null
        favoriteOverride = null
        favoriteOverrideUri = null
        trackDuration = 0.0
        positionBaseTime = 0.0
        positionBaseTimestamp = 0L
        isPlaying = false
        _players.value = emptyList()
        _selectedPlayer.value = null
        _queueState.value = null
        resetPosition()
        queueTracking.clear()
        artistGenreCache.clear()
        libraryArtistUriCache.clear()
        manualSkipByQueue.clear()
        queueReplacementByQueue.clear()
        blockedAutoSkipByQueue.clear()
        blockedQueueCleanupAtByQueue.clear()
        queueFilterModeByQueue.clear()
        volumeOverrideUntilMs.clear()
        suppressedArtistUrisSnapshot = emptySet()
    }

    private suspend fun observeEvents() {
        wsClient.events.collect { event ->
            when (event.event) {
                EventType.PLAYER_UPDATED -> {
                    val serverPlayer = event.data?.let {
                        json.decodeFromJsonElement<ServerPlayer>(it)
                    } ?: return@collect
                    val trackImg = queueTracking[serverPlayer.playerId]?.track?.imageUrl
                    var player = serverPlayer.toDomain(wsClient, trackImg)
                    if (player.activeGroup != null || player.groupChilds.isNotEmpty()) {
                        Log.d(TAG, "Player ${player.displayName} group: activeGroup=${player.activeGroup} childs=${player.groupChilds}")
                    }
                    // During volume cooldown for THIS specific player, preserve
                    // local optimistic values. Other players' echoes still flow
                    // through (needed when group volume fans out to children).
                    if (volumeCooldownActive(player.playerId)) {
                        val local = _players.value.firstOrNull { it.playerId == player.playerId }
                        if (local != null) {
                            player = player.copy(
                                volumeLevel = local.volumeLevel,
                                groupVolume = local.groupVolume ?: player.groupVolume
                            )
                        }
                    }
                    _players.update { list ->
                        list.map { if (it.playerId == player.playerId) player else it }
                    }
                    if (player.playerId == selectedPlayerId) {
                        if (!player.available) {
                            Log.d(TAG, "Selected player became unavailable, deselecting: ${player.displayName}")
                            pendingRestoredPlayerId = player.playerId
                            selectedPlayerId = null
                            _selectedPlayer.value = null
                            _queueState.value = null
                            resetPosition()
                            trackDuration = 0.0
                            favoriteOverride = null
                            favoriteOverrideUri = null
                            stopPositionTicker()
                        } else {
                            _selectedPlayer.value = player
                            val wasPlaying = isPlaying
                            isPlaying = player.state == PlaybackState.PLAYING
                            if (isPlaying && !wasPlaying) startPositionTicker()
                            else if (!isPlaying && wasPlaying) stopPositionTicker()
                        }
                    }
                }
                EventType.PLAYER_ADDED -> {
                    Log.d(TAG, "PLAYER_ADDED event: objectId=${event.objectId}, hasData=${event.data != null}")
                    val serverPlayer = event.data?.let {
                        json.decodeFromJsonElement<ServerPlayer>(it)
                    } ?: return@collect
                    val player = serverPlayer.toDomain(wsClient)
                    Log.d(TAG, "PLAYER_ADDED: ${player.displayName} (${player.playerId})")
                    _players.update { list ->
                        if (list.any { it.playerId == player.playerId }) {
                            list.map { if (it.playerId == player.playerId) player else it }
                        } else list + player
                    }
                    // Auto-select if this is the saved player and nothing is selected yet.
                    if (player.playerId == pendingRestoredPlayerId) {
                        selectPlayer(player.playerId)
                        scope.launch { refreshQueueForPlayerWithRetry(player.playerId) }
                        pendingRestoredPlayerId = null
                        Log.d(TAG, "Auto-selected late-arriving player: ${player.displayName}")
                    }
                }
                EventType.PLAYER_REMOVED -> {
                    Log.d(TAG, "PLAYER_REMOVED event: ${event.objectId}")
                    val id = event.objectId ?: return@collect
                    _players.update { list -> list.filter { it.playerId != id } }
                    queueTracking.remove(id)
                    blockedAutoSkipByQueue.remove(id)
                    blockedQueueCleanupAtByQueue.remove(id)
                    queueFilterModeByQueue.remove(id)
                    if (selectedPlayerId == id) {
                        selectedPlayerId = null
                        _selectedPlayer.value = null
                        _queueState.value = null
                        resetPosition()
                        trackDuration = 0.0
                        favoriteOverride = null
                        favoriteOverrideUri = null
                        stopPositionTicker()
                        scope.launch { settingsRepository.setSelectedPlayerId(null) }
                    }
                }
                EventType.QUEUE_UPDATED -> {
                    val serverQueue = event.data?.let {
                        json.decodeFromJsonElement<ServerQueue>(it)
                    } ?: return@collect

                    // Play history: track all queues, not just selected
                    trackPlayHistory(serverQueue)

                    if (serverQueue.queueId == effectiveQueueId()) {
                        val previousState = _queueState.value
                        var domainState = preserveCurrentAudioFormat(
                            previous = previousState,
                            incoming = serverQueue.toDomain(wsClient)
                        )
                        val currentItemChanged = hasCurrentItemChanged(previousState, domainState)
                        // Apply favorite override if current track matches
                        val override = favoriteOverride
                        val overrideUri = favoriteOverrideUri
                        val currentUri = domainState.currentItem?.track?.uri
                        if (override != null && overrideUri != null && currentUri == overrideUri) {
                            domainState = domainState.copy(
                                currentItem = domainState.currentItem?.copy(
                                    track = domainState.currentItem.track?.copy(favorite = override)
                                )
                            )
                        } else {
                            favoriteOverride = null
                            favoriteOverrideUri = null
                        }
                        _queueState.value = domainState
                        trackDuration = serverQueue.currentItem?.duration ?: 0.0
                        updatePosition(
                            serverElapsed = if (currentItemChanged) 0.0 else serverQueue.elapsedTime,
                            startTicker = !currentItemChanged
                        )
                    }
                }
                EventType.QUEUE_ITEMS_UPDATED -> {
                    val queueId = event.objectId ?: return@collect
                    if (queueId == effectiveQueueId()) {
                        _queueItemsChanged.tryEmit(queueId)
                        scheduleBlockedQueueCleanup(queueId, reason = "queue_items_updated")
                    }
                }
                EventType.QUEUE_TIME_UPDATED -> {
                    if (event.objectId != effectiveQueueId()) return@collect
                    val elapsed = event.data?.jsonPrimitive?.doubleOrNull ?: return@collect
                    updatePosition(elapsed)
                }
            }
        }
    }

    private fun trackPlayHistory(serverQueue: ServerQueue) {
        val mediaItem = serverQueue.currentItem?.mediaItem ?: return
        val trackUri = mediaItem.uri.takeIf { it.isNotBlank() } ?: return
        val queueId = serverQueue.queueId
        val now = System.currentTimeMillis()

        val prev = queueTracking[queueId]
        if (prev != null && prev.track.uri != trackUri) {
            // Track changed: record previous if listened >30s, not manually skipped, and not queue replacement.
            val listenedMs = now - prev.startTime
            val wasManualSkip = manualSkipByQueue.remove(queueId) == prev.track.uri
            val wasQueueReplacement = queueReplacementByQueue.remove(queueId) == prev.track.uri
            if (listenedMs > 30_000L && !wasManualSkip && !wasQueueReplacement) {
                scope.launch {
                    try {
                        val enrichedTrack = enrichTrackGenresForHistory(prev.track, prev.artists)
                        playHistoryRepository.recordPlay(
                            enrichedTrack, queueId, listenedMs, prev.artists
                        )
                        smartListeningRepository.recordListen(
                            track = enrichedTrack,
                            artists = prev.artists,
                            listenedMs = listenedMs
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Play history failed: ${e.message}")
                    }
                }
            }
        }

        if (prev == null || prev.track.uri != trackUri) {
            Log.d(TAG, "New track: $trackUri")
            val artists = mediaItem.artists
                ?.mapNotNull { a ->
                    val rawKey = MediaIdentity.canonicalArtistKey(itemId = a.itemId, uri = a.uri)
                        ?: return@mapNotNull null
                    val key = if (!rawKey.startsWith("library://")) {
                        resolveLibraryArtistUri(a.name, rawKey)
                    } else {
                        rawKey
                    }
                    key to a.name
                } ?: emptyList()

            // Start with track-level metadata genres when present, then enrich from artist cache.
            val trackMetadataGenres = normalizeGenres(mediaItem.metadata?.genres)

            // Resolve genres from cache immediately if available
            val cachedGenres = mediaItem.artists
                ?.flatMap { artistGenreCache[it.uri] ?: emptyList() }
                ?.distinct()
                ?: emptyList()
            val initialGenres = (trackMetadataGenres + cachedGenres)
                .distinct()

            val track = Track(
                itemId = mediaItem.itemId,
                provider = mediaItem.provider,
                name = mediaItem.name,
                uri = mediaItem.uri,
                duration = mediaItem.duration,
                artistNames = mediaItem.artists?.joinToString(", ") { it.name } ?: "",
                albumName = mediaItem.album?.name ?: "",
                imageUrl = mediaItem.resolveImageWithAlbumFallback(wsClient),
                artistItemId = mediaItem.artists?.firstOrNull()?.itemId,
                artistProvider = mediaItem.artists?.firstOrNull()?.provider,
                albumItemId = mediaItem.album?.itemId,
                albumProvider = mediaItem.album?.provider,
                artistUri = mediaItem.artists?.firstOrNull()?.let { a ->
                    val rawKey = MediaIdentity.canonicalArtistKey(itemId = a.itemId, uri = a.uri)
                    if (rawKey != null && !rawKey.startsWith("library://")) {
                        resolveLibraryArtistUri(a.name, rawKey)
                    } else rawKey
                },
                artistUris = mediaItem.artists
                    ?.mapNotNull { artist ->
                        val rawKey = MediaIdentity.canonicalArtistKey(itemId = artist.itemId, uri = artist.uri)
                            ?: return@mapNotNull null
                        if (!rawKey.startsWith("library://")) {
                            resolveLibraryArtistUri(artist.name, rawKey)
                        } else rawKey
                    }
                    ?.distinct()
                    ?: emptyList(),
                albumUri = MediaIdentity.canonicalAlbumKey(
                    itemId = mediaItem.album?.itemId,
                    uri = mediaItem.album?.uri
                ),
                genres = initialGenres,
                year = sanitizeYear(mediaItem.album?.year ?: mediaItem.year),
                lyrics = mediaItem.metadata?.lyrics,
                lrcLyrics = mediaItem.metadata?.lrcLyrics
            )
            queueTracking[queueId] = QueueTrackingState(track, artists, now)
            maybeAutoSkipBlockedTrack(
                queueId = queueId,
                track = track,
                artistUris = artists.map { it.first },
                trigger = "queue_update"
            )
            scheduleBlockedQueueCleanup(queueId, reason = "queue_update")

            // Fetch genres async for uncached artists
            val uncachedArtists = mediaItem.artists
                ?.filter { it.uri.isNotBlank() && !artistGenreCache.containsKey(it.uri) }
                ?: emptyList()
            Log.d(TAG, "Uncached artists for genre fetch: ${uncachedArtists.size}")
            if (uncachedArtists.isNotEmpty()) {
                scope.launch { fetchAndApplyGenres(uncachedArtists, queueId, trackUri) }
            }
        }
    }

    private suspend fun enrichTrackGenresForHistory(
        track: Track,
        artists: List<Pair<String, String>>
    ): Track {
        // Always try Last.fm first (curated whitelist, more accurate than raw provider tags)
        val lastFmGenres = LinkedHashSet<String>()
        artists.forEach { (_, name) ->
            val tags = lastFmGenreResolver.resolve(name)
            if (tags.isNotEmpty()) {
                Log.d(TAG, "History genres from Last.fm '$name': $tags")
                lastFmGenres.addAll(tags)
            }
        }
        if (lastFmGenres.isNotEmpty()) {
            return track.copy(genres = lastFmGenres.toList())
        }

        // Fall back to MA provider genres + artist cache
        val mergedGenres = LinkedHashSet<String>()
        mergedGenres.addAll(normalizeGenres(track.genres))
        artists.forEach { (artistUri, _) ->
            artistGenreCache[artistUri]?.let { mergedGenres.addAll(normalizeGenres(it)) }
        }
        if (mergedGenres.isNotEmpty()) {
            return track.copy(genres = mergedGenres.toList())
        }

        // Fall back to MA metadata chain
        val trackMeta = fetchMediaItem(
            command = MaCommands.Music.TRACKS_GET,
            ref = trackRef(track)
        )
        mergedGenres.addAll(normalizeGenres(trackMeta?.metadata?.genres))
        if (mergedGenres.isNotEmpty()) {
            Log.d(TAG, "History genres from track metadata for ${track.uri}: $mergedGenres")
            return track.copy(genres = mergedGenres.toList())
        }

        val albumMeta = fetchMediaItem(
            command = MaCommands.Music.ALBUMS_GET,
            ref = albumRef(track, trackMeta)
        )
        mergedGenres.addAll(normalizeGenres(albumMeta?.metadata?.genres))
        if (mergedGenres.isNotEmpty()) {
            Log.d(TAG, "History genres from album metadata for ${track.uri}: $mergedGenres")
            return track.copy(genres = mergedGenres.toList())
        }

        val artistRefs = artistRefs(track, trackMeta, artists)
        artistRefs.forEach { ref ->
            val artistMeta = fetchMediaItem(
                command = MaCommands.Music.ARTISTS_GET,
                ref = ref
            )
            val artistGenres = normalizeGenres(artistMeta?.metadata?.genres)
            if (artistGenres.isNotEmpty()) {
                mergedGenres.addAll(artistGenres)
            }
        }
        if (mergedGenres.isNotEmpty()) {
            Log.d(TAG, "History genres from MA artist metadata for ${track.uri}: $mergedGenres")
        } else {
            Log.d(TAG, "No genres found (Last.fm + MA) for ${track.uri}")
        }
        return track.copy(genres = mergedGenres.toList())
    }

    private suspend fun fetchMediaItem(command: String, ref: MaItemRef?): ServerMediaItem? {
        if (ref == null) return null
        return try {
            val result = wsClient.sendCommand(
                command = command,
                args = ItemRefLazyArgs(
                    itemId = ref.itemId,
                    provider = ref.provider,
                    lazy = true
                ),
                timeoutMs = HISTORY_GENRE_LOOKUP_TIMEOUT_MS
            )
            result?.let { json.decodeFromJsonElement<ServerMediaItem>(it) }
        } catch (e: Exception) {
            Log.d(TAG, "History genre lookup failed for $command ${ref.provider}/${ref.itemId}: ${e.message}")
            null
        }
    }

    private fun trackRef(track: Track): MaItemRef? {
        if (!track.itemId.isBlank() && !track.provider.isBlank()) {
            return MaItemRef(itemId = track.itemId, provider = track.provider)
        }
        return parseMaItemRef(track.uri)
    }

    private fun albumRef(track: Track, trackMeta: ServerMediaItem?): MaItemRef? {
        val fromTrackMeta = trackMeta?.album?.let { album ->
            if (album.itemId.isNotBlank() && album.provider.isNotBlank()) {
                MaItemRef(itemId = album.itemId, provider = album.provider)
            } else null
        }
        if (fromTrackMeta != null) return fromTrackMeta

        if (!track.albumItemId.isNullOrBlank() && !track.albumProvider.isNullOrBlank()) {
            return MaItemRef(itemId = track.albumItemId, provider = track.albumProvider)
        }
        return parseMaItemRef(track.albumUri)
    }

    private fun artistRefs(
        track: Track,
        trackMeta: ServerMediaItem?,
        artists: List<Pair<String, String>>
    ): List<MaItemRef> {
        val refs = linkedSetOf<MaItemRef>()

        trackMeta?.artists
            ?.asSequence()
            ?.mapNotNull { artist ->
                if (artist.itemId.isNotBlank() && artist.provider.isNotBlank()) {
                    MaItemRef(itemId = artist.itemId, provider = artist.provider)
                } else parseMaItemRef(artist.uri)
            }
            ?.forEach { refs.add(it) }

        artists
            .asSequence()
            .mapNotNull { (uri, _) -> parseMaItemRef(uri) }
            .forEach { refs.add(it) }

        if (!track.artistItemId.isNullOrBlank() && !track.artistProvider.isNullOrBlank()) {
            refs.add(MaItemRef(itemId = track.artistItemId, provider = track.artistProvider))
        } else {
            parseMaItemRef(track.artistUri)?.let { refs.add(it) }
        }

        return refs.take(HISTORY_ARTIST_LOOKUP_LIMIT)
    }

    private fun parseMaItemRef(uri: String?): MaItemRef? {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val provider = raw.substringBefore("://", "").trim()
        if (provider.isEmpty()) return null
        val path = raw.substringAfter("://", "")
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (path.isEmpty()) return null
        val itemId = path.substringAfterLast('/').trim()
        if (itemId.isEmpty()) return null
        return MaItemRef(itemId = itemId, provider = provider)
    }

    private fun resolveLibraryArtistUri(name: String, fallback: String): String {
        libraryArtistUriCache[name]?.let { return it }
        // Async resolve for next time
        scope.launch {
            val resolved = playHistoryRepository.resolveLibraryArtistUri(name)
            if (resolved != null) libraryArtistUriCache[name] = resolved
        }
        return fallback
    }

    private fun normalizeGenres(genres: List<String>?): List<String> {
        if (genres.isNullOrEmpty()) return emptyList()
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        genres.forEach { raw ->
            val value = raw.trim()
            if (value.isEmpty()) return@forEach
            val key = normalizeGenre(value)
            if (seen.add(key)) {
                result.add(key)
            }
        }
        return result
    }

    private fun sanitizeYear(year: Int?): Int? = year?.takeIf { it > 0 }

    private fun maybeAutoSkipBlockedTrack(
        queueId: String,
        track: Track?,
        artistUris: List<String>,
        trigger: String
    ) {
        if (queueId != selectedPlayerId) return
        val currentTrack = track ?: return
        if (currentTrack.uri.isBlank()) return
        val filteredArtists = currentFilteredArtists(queueId)
        if (filteredArtists.isEmpty()) return

        val distinctUris = artistUris
            .mapNotNull { MediaIdentity.canonicalArtistKey(uri = it) }
            .distinct()
        if (distinctUris.none { it in filteredArtists }) return

        val now = System.currentTimeMillis()
        val last = blockedAutoSkipByQueue[queueId]
        if (last != null && last.first == currentTrack.uri && now - last.second < BLOCKED_AUTO_SKIP_COOLDOWN_MS) {
            return
        }
        blockedAutoSkipByQueue[queueId] = currentTrack.uri to now

        scope.launch {
            try {
                playerCmd("next", queueId)
                Log.d(TAG, "Auto-skipped blocked track (${currentTrack.uri}) via $trigger")
            } catch (e: Exception) {
                Log.w(TAG, "Auto-skip blocked track failed: ${e.message}")
            }
        }
    }

    private fun scheduleBlockedQueueCleanup(queueId: String, reason: String, force: Boolean = false) {
        if (queueId != selectedPlayerId) return
        val now = System.currentTimeMillis()
        val lastAt = blockedQueueCleanupAtByQueue[queueId] ?: 0L
        if (!force && now - lastAt < BLOCKED_QUEUE_CLEANUP_COOLDOWN_MS) return
        blockedQueueCleanupAtByQueue[queueId] = now
        blockedQueueCleanupJob?.cancel()
        blockedQueueCleanupJob = scope.launch {
            purgeBlockedTracksFromQueue(queueId, reason)
        }
    }

    private suspend fun purgeBlockedTracksFromQueue(queueId: String, reason: String) {
        val blocked = blockedArtistUrisSnapshot
        val suppressed = try {
            val smartEnabled = settingsRepository.smartListeningEnabled.first()
            if (smartEnabled && shouldApplySuppressedFiltering(queueId)) {
                smartListeningRepository.getSuppressedArtistUris(days = 120)
            } else {
                emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }
        suppressedArtistUrisSnapshot = suppressed
        val filteredArtists = blocked + suppressed
        if (filteredArtists.isEmpty()) return

        try {
            val result = wsClient.sendCommand(
                MaCommands.PlayerQueues.ITEMS,
                QueueItemsArgs(
                    queueId = queueId,
                    limit = BLOCKED_QUEUE_ITEMS_LIMIT,
                    offset = 0
                )
            )
            val items = result?.let { json.decodeFromJsonElement<List<ServerQueueItem>>(it) } ?: emptyList()
            if (items.isEmpty()) return

            val currentTrackUri = queueTracking[queueId]?.track?.uri
                ?: _queueState.value?.currentItem?.track?.uri
            val removeQueueItemIds = items.mapNotNull { item ->
                val media = item.mediaItem ?: return@mapNotNull null
                val mediaUri = media.uri.takeIf { it.isNotBlank() }
                if (!currentTrackUri.isNullOrBlank() && mediaUri == currentTrackUri) {
                    return@mapNotNull null
                }
                val artistUris = media.artists
                    ?.mapNotNull { artist ->
                        val rawKey = MediaIdentity.canonicalArtistKey(itemId = artist.itemId, uri = artist.uri)
                            ?: return@mapNotNull null
                        if (!rawKey.startsWith("library://")) {
                            resolveLibraryArtistUri(artist.name, rawKey)
                        } else rawKey
                    }
                    .orEmpty()
                if (artistUris.any { it in filteredArtists }) item.queueItemId else null
            }
            if (removeQueueItemIds.isEmpty()) return

            removeQueueItemIds.forEach { itemId ->
                wsClient.sendCommand(
                    MaCommands.PlayerQueues.DELETE_ITEM,
                    DeleteQueueItemArgs(queueId = queueId, itemIdOrIndex = itemId),
                    awaitResponse = false
                )
            }
            Log.d(
                TAG,
                "Removed ${removeQueueItemIds.size} filtered track(s) from queue $queueId via $reason " +
                    "(mode=${queueFilterModeByQueue[queueId] ?: PlayerRepository.QueueFilterMode.NORMAL}, blocked=${blocked.size}, suppressed=${suppressed.size})"
            )
        } catch (e: Exception) {
            Log.w(TAG, "purgeBlockedTracksFromQueue failed: ${e.message}")
        }
    }

    private fun currentFilteredArtists(queueId: String): Set<String> {
        val blocked = blockedArtistUrisSnapshot
        if (!smartListeningEnabledSnapshot || !shouldApplySuppressedFiltering(queueId)) {
            return blocked
        }
        return blocked + suppressedArtistUrisSnapshot
    }

    private fun shouldApplySuppressedFiltering(queueId: String): Boolean {
        return when (queueFilterModeByQueue[queueId] ?: PlayerRepository.QueueFilterMode.NORMAL) {
            PlayerRepository.QueueFilterMode.NORMAL -> false
            PlayerRepository.QueueFilterMode.SMART_GENERATED,
            PlayerRepository.QueueFilterMode.RADIO_SMART -> true
        }
    }

    private suspend fun fetchAndApplyGenres(
        artists: List<ServerMediaItem>,
        queueId: String,
        trackUri: String
    ) {
        Log.d(TAG, "fetchAndApplyGenres: ${artists.map { "${it.name}(${it.itemId})" }}")
        val allGenres = mutableSetOf<String>()
        // Try Last.fm first (better genre data when API key is configured)
        for (artist in artists) {
            val tags = lastFmGenreResolver.resolve(artist.name)
            if (tags.isNotEmpty()) {
                Log.d(TAG, "Last.fm genres for ${artist.name}: $tags")
                allGenres.addAll(tags)
                artistGenreCache[artist.uri] = tags
            }
        }
        // Fall back to MA server if Last.fm returned nothing
        if (allGenres.isEmpty()) {
            for (artist in artists) {
                try {
                    val result = wsClient.sendCommand(
                        MaCommands.Music.ARTISTS_GET,
                        ItemRefLazyArgs(
                            itemId = artist.itemId,
                            provider = artist.provider,
                            lazy = true
                        )
                    )
                    val genres = normalizeGenres(
                        result?.let {
                            json.decodeFromJsonElement<ServerMediaItem>(it)
                        }?.metadata?.genres
                    )
                    Log.d(TAG, "MA genres for ${artist.name}: $genres")
                    artistGenreCache[artist.uri] = genres
                    allGenres.addAll(genres)
                } catch (e: Exception) {
                    Log.w(TAG, "MA genre fetch failed for ${artist.name}: ${e.message}")
                    artistGenreCache[artist.uri] = emptyList()
                }
            }
        }
        // Include already-known genres already attached to the tracked item (track metadata / previous cache).
        queueTracking[queueId]?.track?.genres?.let { allGenres.addAll(it) }
        // Include already-cached genres from other artists on this track
        queueTracking[queueId]?.artists?.forEach { (uri, _) ->
            artistGenreCache[uri]?.let { allGenres.addAll(it) }
        }
        if (allGenres.isNotEmpty()) {
            queueTracking.computeIfPresent(queueId) { _, state ->
                if (state.track.uri == trackUri) {
                    state.copy(track = state.track.copy(genres = allGenres.toList()))
                } else state
            }
            Log.d(TAG, "Genres resolved for $trackUri: $allGenres")
        }
    }

    private fun updatePosition(serverElapsed: Double, startTicker: Boolean = true) {
        positionBaseTime = serverElapsed
        positionBaseTimestamp = System.currentTimeMillis()
        publishPosition(serverElapsed)
        if (startTicker) startPositionTicker() else stopPositionTicker()
    }

    private fun publishPosition(position: Double) {
        _elapsedTime.value = position
        _playbackPosition.value = currentPlaybackPosition(position)
    }

    private fun resetPosition() {
        _elapsedTime.value = 0.0
        _playbackPosition.value = null
    }

    private fun currentPlaybackPosition(position: Double): PlaybackPosition {
        val queue = _queueState.value
        val item = queue?.currentItem
        return PlaybackPosition(
            queueId = queue?.queueId,
            queueItemId = item?.queueItemId,
            trackUri = item?.track?.uri,
            position = position
        )
    }

    private fun preserveCurrentAudioFormat(
        previous: QueueState?,
        incoming: QueueState
    ): QueueState {
        val prevItem = previous?.currentItem ?: return incoming
        val nextItem = incoming.currentItem ?: return incoming
        if (nextItem.audioFormat != null) return incoming

        val sameQueueItem = prevItem.queueItemId.isNotBlank() && prevItem.queueItemId == nextItem.queueItemId
        val prevTrackUri = prevItem.track?.uri
        val nextTrackUri = nextItem.track?.uri
        val sameTrack = !prevTrackUri.isNullOrBlank() && prevTrackUri == nextTrackUri
        if (!sameQueueItem && !sameTrack) return incoming

        val preserved = prevItem.audioFormat ?: return incoming
        return incoming.copy(
            currentItem = nextItem.copy(audioFormat = preserved)
        )
    }

    private fun hasCurrentItemChanged(previous: QueueState?, incoming: QueueState): Boolean {
        val previousItem = previous?.currentItem ?: return false
        val nextItem = incoming.currentItem ?: return false

        val previousQueueItemId = previousItem.queueItemId
        val nextQueueItemId = nextItem.queueItemId
        if (previousQueueItemId.isNotBlank() && nextQueueItemId.isNotBlank()) {
            return previousQueueItemId != nextQueueItemId
        }

        val previousTrackUri = previousItem.track?.uri
        val nextTrackUri = nextItem.track?.uri
        if (!previousTrackUri.isNullOrBlank() && !nextTrackUri.isNullOrBlank()) {
            return previousTrackUri != nextTrackUri
        }

        return false
    }

    private fun startPositionTicker() {
        positionTickJob?.cancel()
        if (!isPlaying) return
        positionTickJob = scope.launch {
            while (isActive) {
                delay(500)
                val elapsed = positionBaseTime + (System.currentTimeMillis() - positionBaseTimestamp) / 1000.0
                publishPosition(if (trackDuration > 0) elapsed.coerceAtMost(trackDuration) else elapsed)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickJob?.cancel()
        positionTickJob = null
    }

    override suspend fun refreshPlayers() {
        try {
            val result = wsClient.sendCommand(MaCommands.Players.ALL)
            val serverPlayers = result?.let { json.decodeFromJsonElement<List<ServerPlayer>>(it) } ?: emptyList()
            Log.d(TAG, "Loaded ${serverPlayers.size} players")
            val fromServer = serverPlayers.map {
                val trackImg = queueTracking[it.playerId]?.track?.imageUrl
                it.toDomain(wsClient, trackImg)
            }
            // Merge: server snapshot + recently event-added players not yet in snapshot
            val serverIds = fromServer.map { it.playerId }.toSet()
            val retained = _players.value.filter { it.playerId !in serverIds && it.available }
            if (retained.isNotEmpty()) {
                Log.d(TAG, "Retaining ${retained.size} event-added players: ${retained.map { it.displayName }}")
            }
            _players.value = fromServer + retained

            if (selectedPlayerId != null) {
                val refreshedSelected = _players.value.find { it.playerId == selectedPlayerId }
                if (refreshedSelected != null) {
                    _selectedPlayer.value = refreshedSelected
                    if (pendingRestoredPlayerId == refreshedSelected.playerId) {
                        pendingRestoredPlayerId = null
                    }
                } else if (pendingRestoredPlayerId == selectedPlayerId) {
                    Log.d(TAG, "Selected player not in refresh snapshot yet, waiting for late-arriving player: $selectedPlayerId")
                } else {
                    clearSelectedPlayer("Selected player missing after refresh")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh players: ${e.message}", e)
        }
    }

    private fun clearSelectedPlayer(reason: String) {
        Log.d(TAG, reason)
        selectedPlayerId = null
        pendingRestoredPlayerId = null
        _selectedPlayer.value = null
        _queueState.value = null
        resetPosition()
        trackDuration = 0.0
        favoriteOverride = null
        favoriteOverrideUri = null
        stopPositionTicker()
        scope.launch { settingsRepository.setSelectedPlayerId(null) }
    }

    override fun selectPlayer(playerId: String) {
        val lockedPlayerId = _selectionLock.value?.playerId
        val effectivePlayerId = lockedPlayerId ?: playerId
        if (lockedPlayerId != null && playerId != lockedPlayerId) {
            Log.d(TAG, "Selection locked to $lockedPlayerId; ignored selectPlayer($playerId)")
        }
        val player = _players.value.find { it.playerId == effectivePlayerId }
        if (
            selectedPlayerId == effectivePlayerId &&
            _selectedPlayer.value?.playerId == effectivePlayerId
        ) {
            return
        }

        selectedPlayerId = effectivePlayerId
        pendingRestoredPlayerId = if (pendingRestoredPlayerId == effectivePlayerId) null else pendingRestoredPlayerId
        _selectedPlayer.value = player
        isPlaying = player?.state == PlaybackState.PLAYING
        stopPositionTicker()
        scope.launch {
            settingsRepository.setSelectedPlayerId(effectivePlayerId)
            refreshQueueForPlayerWithRetry(effectivePlayerId)
            scheduleBlockedQueueCleanup(effectivePlayerId, reason = "select_player", force = true)
        }
    }

    override fun setSelectionLock(lock: PlayerSelectionLock?) {
        val previous = _selectionLock.value
        if (previous == lock) return
        _selectionLock.value = lock
        if (lock != null && selectedPlayerId != lock.playerId) {
            Log.d(TAG, "Applying selection lock to ${lock.playerId} (${lock.reason})")
            selectPlayer(lock.playerId)
        } else if (lock == null && previous != null) {
            Log.d(TAG, "Selection lock cleared (${previous.reason})")
        }
    }

    override fun setQueueFilterMode(playerId: String, mode: PlayerRepository.QueueFilterMode) {
        queueFilterModeByQueue[playerId] = mode
        if (playerId == selectedPlayerId) {
            scheduleBlockedQueueCleanup(playerId, reason = "queue_filter_mode", force = true)
        }
    }

    private suspend fun refreshQueueForPlayer(playerId: String) {
        try {
            val result = wsClient.sendCommand(
                MaCommands.PlayerQueues.GET_ACTIVE_QUEUE,
                ActiveQueueArgs(playerId = playerId)
            )
            val serverQueue = result?.let { json.decodeFromJsonElement<ServerQueue>(it) }
            _queueState.value = serverQueue?.toDomain(wsClient)
            if (serverQueue != null) {
                trackDuration = serverQueue.currentItem?.duration ?: 0.0
                updatePosition(serverQueue.elapsedTime)
            } else {
                resetPosition()
                trackDuration = 0.0
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshQueueForPlayer($playerId) failed: ${e.message}")
            throw e
        }
    }

    private suspend fun refreshQueueForPlayerWithRetry(playerId: String) {
        repeat(RECONNECT_QUEUE_REFRESH_ATTEMPTS) { attempt ->
            try {
                refreshQueueForPlayer(playerId)
                return
            } catch (_: Exception) {
                if (attempt == RECONNECT_QUEUE_REFRESH_ATTEMPTS - 1) return@repeat
                delay(RECONNECT_QUEUE_REFRESH_DELAY_MS * (attempt + 1))
            }
        }
    }

    override suspend fun play(playerId: String) {
        _playbackIntent.tryEmit(true)
        playerCmd("play", playerId)
    }

    override suspend fun pause(playerId: String) {
        _playbackIntent.tryEmit(false)
        playerCmd("pause", playerId)
    }

    override suspend fun playPause(playerId: String) {
        val willPlay = _selectedPlayer.value?.state != PlaybackState.PLAYING
        _playbackIntent.tryEmit(willPlay)
        playerCmd("play_pause", playerId)
    }

    override fun notifyPlaybackIntent(willPlay: Boolean) {
        _playbackIntent.tryEmit(willPlay)
    }

    override fun isArtistUriBlocked(artistUri: String): Boolean =
        artistUri in blockedArtistUrisSnapshot

    override fun isArtistBlocked(artistName: String, artistUri: String): Boolean {
        val resolved = if (!artistUri.startsWith("library://")) {
            resolveLibraryArtistUri(artistName, artistUri)
        } else artistUri
        return resolved in blockedArtistUrisSnapshot
    }

    override fun hasBlockedArtists(): Boolean =
        blockedArtistUrisSnapshot.isNotEmpty()

    override suspend fun next(playerId: String) {
        Log.d("sendspindbg", "WS>>> next($playerId)")
        maybeRecordManualSkip(playerId)
        _discontinuityCommands.tryEmit(PlayerDiscontinuityCommand(playerId, PlayerDiscontinuityCommand.Kind.NEXT))
        playerCmd("next", playerId)
    }

    override suspend fun previous(playerId: String) {
        Log.d("sendspindbg", "WS>>> previous($playerId)")
        maybeRecordManualSkip(playerId)
        _discontinuityCommands.tryEmit(PlayerDiscontinuityCommand(playerId, PlayerDiscontinuityCommand.Kind.PREVIOUS))
        playerCmd("previous", playerId)
    }

    override suspend fun seek(playerId: String, position: Double) {
        Log.d("sendspindbg", "WS>>> seek($playerId, ${position}s)")
        _discontinuityCommands.tryEmit(PlayerDiscontinuityCommand(playerId, PlayerDiscontinuityCommand.Kind.SEEK))
        sendPlayerCommandWithRetry(
            MaCommands.Players.CMD_SEEK,
            SeekArgs(playerId = playerId, position = position)
        )
    }

    // Per-player cooldowns so that an optimistic update on one player doesn't
    // suppress server echoes for OTHER players. Group volume fans out to
    // children — we need their echoes to arrive.
    private val volumeOverrideUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun volumeCooldownActive(playerId: String): Boolean =
        System.currentTimeMillis() < (volumeOverrideUntilMs[playerId] ?: 0L)

    /**
     * Synchronous optimistic volume update + cooldown.
     * Call from the UI thread BEFORE launching the async WS command
     * to prevent PLAYER_UPDATED echoes from flickering the slider.
     *
     * For group-type players we also update [Player.groupVolume] so that the
     * hardware rocker keeps incrementing from the new basis instead of the
     * stale server average.
     */
    override fun applyVolumeOptimistic(playerId: String, volumeLevel: Int) {
        volumeOverrideUntilMs[playerId] = System.currentTimeMillis() + 800
        _players.update { list ->
            list.map {
                if (it.playerId != playerId) return@map it
                val patched = it.copy(volumeLevel = volumeLevel)
                if (it.groupVolume != null) patched.copy(groupVolume = volumeLevel) else patched
            }
        }
        if (_selectedPlayer.value?.playerId == playerId) {
            _selectedPlayer.update {
                val s = it ?: return@update null
                val patched = s.copy(volumeLevel = volumeLevel)
                if (s.groupVolume != null) patched.copy(groupVolume = volumeLevel) else patched
            }
        }
    }

    override suspend fun setVolume(playerId: String, volumeLevel: Int) {
        applyVolumeOptimistic(playerId, volumeLevel)
        wsClient.sendCommand(
            MaCommands.Players.CMD_VOLUME_SET,
            VolumeSetArgs(playerId = playerId, volumeLevel = volumeLevel)
        )
    }

    override suspend fun setGroupVolume(parentId: String, volume: Int) {
        // Delegate to the server-side group-volume command. It handles the fan-out
        // to members while preserving their current volume ratios, for both
        // proper group players (type=GROUP) and ad-hoc sync leaders.
        applyVolumeOptimistic(parentId, volume)
        wsClient.sendCommand(
            MaCommands.Players.CMD_GROUP_VOLUME,
            VolumeSetArgs(playerId = parentId, volumeLevel = volume)
        )
    }

    override fun updateGroupMemberOffset(parentId: String, memberId: String, volume: Int) {
        // No-op: group volume ratios are now tracked server-side. Individual
        // member volume changes go straight through setVolume() and the server
        // preserves the new ratio on the next group-volume command.
    }

    override suspend fun toggleMute(playerId: String, muted: Boolean) {
        wsClient.sendCommand(
            MaCommands.Players.CMD_VOLUME_MUTE,
            VolumeMuteArgs(playerId = playerId, muted = muted)
        )
    }

    override suspend fun setPower(playerId: String, powered: Boolean) {
        // Optimistic local flip so the switch in Player Settings reacts
        // immediately; the authoritative value lands with the next
        // PLAYER_UPDATED event.
        _players.update { list ->
            list.map { if (it.playerId == playerId) it.copy(powered = powered) else it }
        }
        if (_selectedPlayer.value?.playerId == playerId) {
            _selectedPlayer.update { it?.copy(powered = powered) }
        }
        wsClient.sendCommand(
            MaCommands.Players.CMD_POWER,
            PowerArgs(playerId = playerId, powered = powered)
        )
    }

    override suspend fun updatePlayerIcon(playerId: String, icon: String) {
        wsClient.sendCommand(
            MaCommands.ConfigPlayers.SAVE,
            ConfigPlayerSaveArgs(
                playerId = playerId,
                values = buildJsonObject { put("icon", icon) }
            )
        )
        // Update local state immediately
        _players.update { list ->
            list.map { if (it.playerId == playerId) it.copy(icon = icon) else it }
        }
        if (selectedPlayerId == playerId) {
            _selectedPlayer.update { it?.copy(icon = icon) }
        }
    }

    override suspend fun renamePlayer(playerId: String, name: String) {
        wsClient.sendCommand(
            MaCommands.ConfigPlayers.SAVE,
            ConfigPlayerSaveArgs(
                playerId = playerId,
                values = buildJsonObject { put("name", name) }
            )
        )
        _players.update { list ->
            list.map { if (it.playerId == playerId) it.copy(displayName = name) else it }
        }
        if (selectedPlayerId == playerId) {
            _selectedPlayer.update { it?.copy(displayName = name) }
        }
    }

    override suspend fun getPlayerConfig(playerId: String): PlayerConfig? {
        val result = wsClient.sendCommand(
            MaCommands.ConfigPlayers.GET,
            ConfigPlayerGetArgs(playerId = playerId)
        )
        return try {
            val obj = result?.jsonObject ?: return null
            val values = obj["values"]?.jsonObject
            fun JsonElement.configValue(): String? = when (this) {
                is JsonPrimitive -> contentOrNull
                is JsonObject -> this["value"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
            fun JsonElement.configBool(): Boolean? = when (this) {
                is JsonPrimitive -> booleanOrNull
                is JsonObject -> this["value"]?.jsonPrimitive?.booleanOrNull
                else -> null
            }
            fun JsonElement.configInt(): Int? = when (this) {
                is JsonPrimitive -> intOrNull
                is JsonObject -> this["value"]?.jsonPrimitive?.intOrNull
                else -> null
            }

            val configName = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["default_name"]?.jsonPrimitive?.contentOrNull ?: ""

            val formatEntry = values?.get("preferred_sendspin_format")
            val formatOptions = (formatEntry as? JsonObject)?.get("options")
                ?.jsonArray
                ?.mapNotNull { opt ->
                    val o = opt.jsonObject
                    val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val value = o["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    FormatOption(title, value)
                }
                ?: emptyList()

            PlayerConfig(
                name = configName,
                crossfadeMode = CrossfadeMode.fromApi(
                    values?.get("smart_fades_mode")?.configValue() ?: "disabled"
                ),
                volumeNormalization = values?.get("volume_normalization")?.configBool() ?: false,
                sendspinFormat = formatEntry?.configValue(),
                sendspinFormatOptions = formatOptions,
                sendspinStaticDelayMs = values?.get("sendspin_static_delay")?.configInt()
            )
        } catch (e: Exception) {
            Log.w(TAG, "getPlayerConfig failed: ${e.message}")
            null
        }
    }

    override suspend fun savePlayerConfig(playerId: String, values: Map<String, Any>) {
        wsClient.sendCommand(
            MaCommands.ConfigPlayers.SAVE,
            ConfigPlayerSaveArgs(
                playerId = playerId,
                values = buildJsonObject {
                    values.forEach { (key, value) ->
                        when (value) {
                            is String -> put(key, value)
                            is Boolean -> put(key, value)
                            is Int -> put(key, value)
                            else -> put(key, value.toString())
                        }
                    }
                }
            )
        )
    }

    override fun updateCurrentTrackFavorite(favorite: Boolean) {
        favoriteOverride = favorite
        favoriteOverrideUri = _queueState.value?.currentItem?.track?.uri
        _queueState.update { qs ->
            qs?.copy(currentItem = qs.currentItem?.copy(
                track = qs.currentItem.track?.copy(favorite = favorite)
            ))
        }
    }

    override fun updateCurrentTrackLyrics(plainLyrics: String?, lrcLyrics: String?) {
        val currentUri = _queueState.value?.currentItem?.track?.uri ?: return
        _queueState.update { qs ->
            val track = qs?.currentItem?.track ?: return@update qs
            if (track.uri != currentUri) return@update qs
            qs.copy(
                currentItem = qs.currentItem.copy(
                    track = track.copy(
                        lyrics = plainLyrics,
                        lrcLyrics = lrcLyrics
                    )
                )
            )
        }
    }

    override fun notifyQueueReplacement(queueId: String) {
        val current = queueTracking[queueId] ?: return
        queueReplacementByQueue[queueId] = current.track.uri
        Log.d(TAG, "Queue replacement flagged for ${current.track.name}")
    }

    override suspend fun getGroupCapableProviders(): List<GroupProviderOption> {
        val result = wsClient.sendCommand(MaCommands.Providers.LIST) ?: return emptyList()
        return try {
            val instances = json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ProviderInstance.serializer()),
                result
            )
            instances
                .filter { it.type == "player" && "create_group_player" in it.supportedFeatures }
                .map { GroupProviderOption(instanceId = it.instanceId, domain = it.domain, name = it.name) }
        } catch (e: Exception) {
            Log.w(TAG, "getGroupCapableProviders parse failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun createGroupPlayer(provider: String, name: String, memberIds: List<String>) {
        wsClient.sendCommand(
            MaCommands.Players.CREATE_GROUP,
            CreateGroupPlayerArgs(provider = provider, name = name, members = memberIds)
        )
    }

    override suspend fun setGroupMembers(targetPlayerId: String, addIds: List<String>?, removeIds: List<String>?) {
        wsClient.sendCommand(
            MaCommands.Players.CMD_SET_MEMBERS,
            SetMembersArgs(targetPlayer = targetPlayerId, playerIdsToAdd = addIds, playerIdsToRemove = removeIds)
        )
    }

    override suspend fun ungroupPlayer(playerId: String) {
        wsClient.sendCommand(
            MaCommands.Players.UNGROUP,
            UngroupArgs(playerId = playerId)
        )
    }

    override suspend fun ungroupPlayers(playerIds: List<String>) {
        if (playerIds.isEmpty()) return
        wsClient.sendCommand(
            MaCommands.Players.UNGROUP_MANY,
            UngroupManyArgs(playerIds = playerIds)
        )
    }

    override suspend fun removeGroupPlayer(playerId: String) {
        wsClient.sendCommand(
            MaCommands.Players.REMOVE_GROUP,
            RemoveGroupPlayerArgs(playerId = playerId)
        )
    }

    private fun maybeRecordManualSkip(playerId: String) {
        val current = queueTracking[playerId] ?: return
        if (!smartListeningEnabledSnapshot) return
        manualSkipByQueue[playerId] = current.track.uri
        val listenedMs = System.currentTimeMillis() - current.startTime
        scope.launch {
            try {
                smartListeningRepository.recordSkip(current.track, current.artists, listenedMs)
            } catch (e: Exception) {
                Log.w(TAG, "Skip signal failed: ${e.message}")
            }
        }
    }

    private suspend fun playerCmd(cmd: String, playerId: String) {
        sendPlayerCommandWithRetry(
            MaCommands.Players.cmd(cmd),
            PlayerIdArgs(playerId = playerId)
        )
        Log.d(TAG, "playerCmd sent: $cmd($playerId)")
    }

    private suspend fun sendPlayerCommandWithRetry(command: String, args: MaCommandArgs) {
        var attempt = 0
        var lastError: MaApiException? = null
        while (attempt < 2) {
            try {
                wsClient.sendCommand(command, args, awaitResponse = false)
                return
            } catch (e: MaApiException) {
                if (!isTransientWsCommandError(e)) throw e
                lastError = e
                attempt++
                if (attempt >= 2) break
                Log.d(TAG, "Transient '$command' failure, waiting for reconnect and retrying")
                withTimeoutOrNull(2_000) {
                    wsClient.connectionState.first { it is ConnectionState.Connected }
                }
                delay(250)
            }
        }
        throw lastError ?: MaApiException("WebSocket command failed", -1)
    }

    private fun isTransientWsCommandError(e: MaApiException): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return "websocket not connected" in msg ||
                "connection lost" in msg ||
                "connection closed" in msg
    }
}

fun ServerPlayer.toDomain(
    wsClient: MaWebSocketClient,
    queueTrackImageUrl: String? = null
): Player = Player(
    playerId = playerId,
    displayName = displayName.ifBlank { name },
    provider = provider,
    type = when (type) {
        "group" -> PlayerType.GROUP
        "stereo_pair" -> PlayerType.STEREO_PAIR
        else -> PlayerType.PLAYER
    },
    available = available,
    state = when (state) {
        "playing" -> PlaybackState.PLAYING
        "paused" -> PlaybackState.PAUSED
        else -> PlaybackState.IDLE
    },
    powered = powered,
    volumeLevel = volumeLevel,
    groupVolume = groupVolume,
    volumeMuted = volumeMuted,
    activeGroup = activeGroup,
    syncedTo = syncedTo,
    groupChilds = groupChilds,
    staticGroupMembers = staticGroupMembers,
    supportedFeatures = supportedFeatures.toSet(),
    canGroupWith = canGroupWith,
    currentMedia = currentMedia?.let {
        NowPlaying(
            queueId = it.queueId,
            title = it.title ?: "",
            artist = it.artist ?: "",
            album = it.album ?: "",
            imageUrl = queueTrackImageUrl
                ?: it.imageUrl?.let { url -> wsClient.rewriteImageProxyUrl(url) }
                ?: it.image?.resolveUrl(wsClient),
            duration = it.duration ?: 0.0,
            elapsedTime = it.elapsedTime ?: 0.0,
            uri = it.uri
        )
    },
    icon = icon?.let { value ->
        when {
            value.startsWith("/") -> wsClient.getImageUrl(value)
            value.contains("://") -> value
            else -> value // MDI name, pass through
        }
    }
)

fun ServerQueue.toDomain(wsClient: MaWebSocketClient): QueueState = QueueState(
    queueId = queueId,
    shuffleEnabled = shuffleEnabled,
    repeatMode = RepeatMode.fromApi(repeatMode),
    elapsedTime = elapsedTime,
    currentIndex = currentIndex,
    dontStopTheMusicEnabled = dontStopTheMusicEnabled,
    currentItem = currentItem?.let { item ->
        QueueItem(
            queueItemId = item.queueItemId,
            name = item.name,
            duration = item.duration,
            track = item.mediaItem?.let { mi ->
                Track(
                    itemId = mi.itemId,
                    provider = mi.provider,
                    name = mi.name,
                    uri = mi.uri,
                    duration = mi.duration,
                    artistNames = mi.artists?.joinToString(", ") { it.name } ?: "",
                    albumName = mi.album?.name ?: "",
                    imageUrl = mi.resolveImageWithAlbumFallback(wsClient),
                    favorite = mi.favorite,
                    artistItemId = mi.artists?.firstOrNull()?.itemId,
                    artistProvider = mi.artists?.firstOrNull()?.provider,
                    albumItemId = mi.album?.itemId,
                    albumProvider = mi.album?.provider,
                    artistUri = MediaIdentity.canonicalArtistKey(
                        itemId = mi.artists?.firstOrNull()?.itemId,
                        uri = mi.artists?.firstOrNull()?.uri
                    ),
                    artistUris = mi.artists
                        ?.mapNotNull { artist ->
                            MediaIdentity.canonicalArtistKey(itemId = artist.itemId, uri = artist.uri)
                        }
                        ?.distinct()
                        ?: emptyList(),
                    albumUri = MediaIdentity.canonicalAlbumKey(
                        itemId = mi.album?.itemId,
                        uri = mi.album?.uri
                    ),
                    genres = mi.metadata?.genres ?: emptyList(),
                    year = (mi.album?.year ?: mi.year)?.takeIf { it > 0 },
                    lyrics = mi.metadata?.lyrics,
                    lrcLyrics = mi.metadata?.lrcLyrics
                )
            },
            imageUrl = item.mediaItem?.resolveImageUrl(wsClient)
                ?: item.image?.resolveUrl(wsClient),
            audioFormat = item.streamdetails?.audioFormat?.let { format ->
                AudioFormatInfo(
                    contentType = format.contentType,
                    sampleRate = format.sampleRate,
                    bitDepth = format.bitDepth,
                    bitRate = format.bitRate,
                    channels = format.channels
                )
            }
        )
    }
)
