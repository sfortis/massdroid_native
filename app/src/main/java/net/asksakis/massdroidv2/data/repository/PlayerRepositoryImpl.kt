package net.asksakis.massdroidv2.data.repository

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.asksakis.massdroidv2.data.websocket.*
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val json: Json,
    private val playHistoryRepository: PlayHistoryRepository,
    private val settingsRepository: SettingsRepository
) : PlayerRepository {

    companion object {
        private const val TAG = "PlayerRepo"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _players = MutableStateFlow<List<Player>>(emptyList())
    override val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _selectedPlayer = MutableStateFlow<Player?>(null)
    override val selectedPlayer: StateFlow<Player?> = _selectedPlayer.asStateFlow()

    private val _queueState = MutableStateFlow<QueueState?>(null)
    override val queueState: StateFlow<QueueState?> = _queueState.asStateFlow()

    private val _elapsedTime = MutableStateFlow(0.0)
    override val elapsedTime: StateFlow<Double> = _elapsedTime.asStateFlow()

    private val _playbackIntent = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    override val playbackIntent: SharedFlow<Boolean> = _playbackIntent.asSharedFlow()

    private val _queueItemsChanged = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val queueItemsChanged: SharedFlow<String> = _queueItemsChanged.asSharedFlow()

    private var selectedPlayerId: String? = null

    // Position tracking for smooth seek bar updates
    private var positionBaseTime = 0.0
    private var positionBaseTimestamp = 0L
    private var isPlaying = false
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
    private val queueTracking = ConcurrentHashMap<String, QueueTrackingState>()
    private val artistGenreCache = ConcurrentHashMap<String, List<String>>()

    init {
        scope.launch { observeEvents() }
        scope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected, is ConnectionState.Connecting -> {
                        _players.value = emptyList()
                        _selectedPlayer.value = null
                        _queueState.value = null
                        _elapsedTime.value = 0.0
                        stopPositionTicker()
                        queueTracking.clear()
                        artistGenreCache.clear()
                    }
                    is ConnectionState.Connected -> {
                        refreshPlayers()
                        settingsRepository.selectedPlayerId.first()?.let { id ->
                            selectPlayer(id)
                            Log.d(TAG, "Restored saved player: $id")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun observeEvents() {
        wsClient.events.collect { event ->
            when (event.event) {
                EventType.PLAYER_UPDATED -> {
                    val serverPlayer = event.data?.let {
                        json.decodeFromJsonElement<ServerPlayer>(it)
                    } ?: return@collect
                    val trackImg = queueTracking[serverPlayer.playerId]?.track?.imageUrl
                    val player = serverPlayer.toDomain(wsClient, trackImg)
                    _players.update { list ->
                        list.map { if (it.playerId == player.playerId) player else it }
                    }
                    if (player.playerId == selectedPlayerId) {
                        _selectedPlayer.value = player
                        val wasPlaying = isPlaying
                        isPlaying = player.state == PlaybackState.PLAYING
                        if (isPlaying && !wasPlaying) startPositionTicker()
                        else if (!isPlaying && wasPlaying) stopPositionTicker()
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
                    // Auto-select if this is the saved player and nothing is selected yet
                    if (_selectedPlayer.value == null && player.playerId == selectedPlayerId) {
                        selectPlayer(player.playerId)
                        Log.d(TAG, "Auto-selected late-arriving player: ${player.displayName}")
                    }
                }
                EventType.PLAYER_REMOVED -> {
                    Log.d(TAG, "PLAYER_REMOVED event: ${event.objectId}")
                    val id = event.objectId ?: return@collect
                    _players.update { list -> list.filter { it.playerId != id } }
                    queueTracking.remove(id)
                    if (selectedPlayerId == id) {
                        selectedPlayerId = null
                        _selectedPlayer.value = null
                        _queueState.value = null
                        _elapsedTime.value = 0.0
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

                    if (serverQueue.queueId == selectedPlayerId) {
                        var domainState = serverQueue.toDomain(wsClient)
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
                        updatePosition(serverQueue.elapsedTime)
                        _queueItemsChanged.tryEmit(serverQueue.queueId)
                    }
                }
                EventType.QUEUE_ITEMS_UPDATED -> {
                    val queueId = event.objectId ?: return@collect
                    if (queueId == selectedPlayerId) {
                        _queueItemsChanged.tryEmit(queueId)
                    }
                }
                EventType.QUEUE_TIME_UPDATED -> {
                    if (event.objectId != selectedPlayerId) return@collect
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
            // Track changed: record previous if listened >30s
            val listenedMs = now - prev.startTime
            if (listenedMs > 30_000L) {
                scope.launch {
                    try {
                        playHistoryRepository.recordPlay(
                            prev.track, queueId, listenedMs, prev.artists
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Play history failed: ${e.message}")
                    }
                }
            }
        }

        if (prev == null || prev.track.uri != trackUri) {
            Log.d(TAG, "New track: $trackUri, artists=${mediaItem.artists?.map {
                "${it.name}(uri=${it.uri}, id=${it.itemId}, prov=${it.provider})"
            }}")
            val artists = mediaItem.artists
                ?.mapNotNull { a ->
                    a.uri.takeIf { it.isNotBlank() }?.let { uri -> uri to a.name }
                } ?: emptyList()

            // Resolve genres from cache immediately if available
            val cachedGenres = mediaItem.artists
                ?.flatMap { artistGenreCache[it.uri] ?: emptyList() }
                ?.distinct()
                ?: emptyList()

            val track = Track(
                itemId = mediaItem.itemId,
                provider = mediaItem.provider,
                name = mediaItem.name,
                uri = mediaItem.uri,
                duration = mediaItem.duration,
                artistNames = mediaItem.artists?.joinToString(", ") { it.name } ?: "",
                albumName = mediaItem.album?.name ?: "",
                imageUrl = mediaItem.resolveImageUrl(wsClient)
                    ?: mediaItem.album?.resolveImageUrl(wsClient),
                artistUri = mediaItem.artists?.firstOrNull()?.uri,
                albumUri = mediaItem.album?.uri,
                genres = cachedGenres,
                year = mediaItem.album?.year ?: mediaItem.year
            )
            queueTracking[queueId] = QueueTrackingState(track, artists, now)

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

    private suspend fun fetchAndApplyGenres(
        artists: List<ServerMediaItem>,
        queueId: String,
        trackUri: String
    ) {
        Log.d(TAG, "fetchAndApplyGenres: ${artists.map { "${it.name}(${it.itemId})" }}")
        val allGenres = mutableSetOf<String>()
        for (artist in artists) {
            try {
                val result = wsClient.sendCommand("music/artists/get", buildJsonObject {
                    put("item_id", artist.itemId)
                    put("provider_instance_id_or_domain", artist.provider)
                    put("lazy", true)
                })
                val genres = result?.let {
                    json.decodeFromJsonElement<ServerMediaItem>(it)
                }?.metadata?.genres ?: emptyList()
                Log.d(TAG, "Artist ${artist.name} genres: $genres")
                artistGenreCache[artist.uri] = genres
                allGenres.addAll(genres)
            } catch (e: Exception) {
                Log.w(TAG, "Genre fetch failed for ${artist.name}: ${e.message}")
                artistGenreCache[artist.uri] = emptyList()
            }
        }
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

    private fun updatePosition(serverElapsed: Double) {
        positionBaseTime = serverElapsed
        positionBaseTimestamp = System.currentTimeMillis()
        _elapsedTime.value = serverElapsed
        startPositionTicker()
    }

    private fun startPositionTicker() {
        positionTickJob?.cancel()
        if (!isPlaying) return
        positionTickJob = scope.launch {
            while (isActive) {
                delay(500)
                val elapsed = positionBaseTime + (System.currentTimeMillis() - positionBaseTimestamp) / 1000.0
                _elapsedTime.value = if (trackDuration > 0) elapsed.coerceAtMost(trackDuration) else elapsed
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickJob?.cancel()
        positionTickJob = null
    }

    override suspend fun refreshPlayers() {
        try {
            val result = wsClient.sendCommand("players/all")
            val serverPlayers = result?.let { json.decodeFromJsonElement<List<ServerPlayer>>(it) } ?: emptyList()
            Log.d(TAG, "Loaded ${serverPlayers.size} players")
            val fromServer = serverPlayers.map {
                val trackImg = queueTracking[it.playerId]?.track?.imageUrl
                it.toDomain(wsClient, trackImg)
            }
            val serverIds = fromServer.map { it.playerId }.toSet()
            _players.update { currentList ->
                val eventOnly = currentList.filter { it.playerId !in serverIds }
                fromServer + eventOnly
            }

            if (selectedPlayerId != null) {
                _selectedPlayer.value = _players.value.find { it.playerId == selectedPlayerId }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh players: ${e.message}", e)
        }
    }

    override fun selectPlayer(playerId: String) {
        selectedPlayerId = playerId
        val player = _players.value.find { it.playerId == playerId }
        _selectedPlayer.value = player
        isPlaying = player?.state == PlaybackState.PLAYING
        stopPositionTicker()
        scope.launch {
            settingsRepository.setSelectedPlayerId(playerId)
            refreshQueueForPlayer(playerId)
        }
    }

    private suspend fun refreshQueueForPlayer(playerId: String) {
        try {
            val result = wsClient.sendCommand("player_queues/get_active_queue", buildJsonObject {
                put("player_id", playerId)
            })
            val serverQueue = result?.let { json.decodeFromJsonElement<ServerQueue>(it) }
            _queueState.value = serverQueue?.toDomain(wsClient)
            if (serverQueue != null) {
                trackDuration = serverQueue.currentItem?.duration ?: 0.0
                updatePosition(serverQueue.elapsedTime)
            }
        } catch (_: Exception) { }
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
    override suspend fun next(playerId: String) = playerCmd("next", playerId)
    override suspend fun previous(playerId: String) = playerCmd("previous", playerId)

    override suspend fun seek(playerId: String, position: Double) {
        wsClient.sendCommand("players/cmd/seek", buildJsonObject {
            put("player_id", playerId)
            put("position", position)
        })
    }

    override suspend fun setVolume(playerId: String, volumeLevel: Int) {
        wsClient.sendCommand("players/cmd/volume_set", buildJsonObject {
            put("player_id", playerId)
            put("volume_level", volumeLevel)
        })
    }

    override suspend fun toggleMute(playerId: String, muted: Boolean) {
        wsClient.sendCommand("players/cmd/volume_mute", buildJsonObject {
            put("player_id", playerId)
            put("muted", muted)
        })
    }

    override suspend fun updatePlayerIcon(playerId: String, icon: String) {
        wsClient.sendCommand("config/players/save", buildJsonObject {
            put("player_id", playerId)
            put("values", buildJsonObject {
                put("icon", icon)
            })
        })
        // Update local state immediately
        _players.update { list ->
            list.map { if (it.playerId == playerId) it.copy(icon = icon) else it }
        }
        if (selectedPlayerId == playerId) {
            _selectedPlayer.update { it?.copy(icon = icon) }
        }
    }

    override suspend fun renamePlayer(playerId: String, name: String) {
        wsClient.sendCommand("config/players/save", buildJsonObject {
            put("player_id", playerId)
            put("values", buildJsonObject {
                put("name", name)
            })
        })
        _players.update { list ->
            list.map { if (it.playerId == playerId) it.copy(displayName = name) else it }
        }
        if (selectedPlayerId == playerId) {
            _selectedPlayer.update { it?.copy(displayName = name) }
        }
    }

    override suspend fun getPlayerConfig(playerId: String): PlayerConfig? {
        val result = wsClient.sendCommand("config/players/get", buildJsonObject {
            put("player_id", playerId)
        })
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

            val configName = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["default_name"]?.jsonPrimitive?.contentOrNull ?: ""

            PlayerConfig(
                name = configName,
                crossfadeMode = CrossfadeMode.fromApi(
                    values?.get("smart_fades_mode")?.configValue() ?: "disabled"
                ),
                volumeNormalization = values?.get("volume_normalization")?.configBool() ?: false
            )
        } catch (e: Exception) {
            Log.w(TAG, "getPlayerConfig failed: ${e.message}")
            null
        }
    }

    override suspend fun savePlayerConfig(playerId: String, values: Map<String, Any>) {
        wsClient.sendCommand("config/players/save", buildJsonObject {
            put("player_id", playerId)
            put("values", buildJsonObject {
                values.forEach { (key, value) ->
                    when (value) {
                        is String -> put(key, value)
                        is Boolean -> put(key, value)
                        is Int -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            })
        })
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

    private suspend fun playerCmd(cmd: String, playerId: String) {
        val result = wsClient.sendCommand("players/cmd/$cmd", buildJsonObject {
            put("player_id", playerId)
        })
        Log.d(TAG, "playerCmd $cmd($playerId) -> $result")
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
    volumeLevel = volumeLevel,
    volumeMuted = volumeMuted,
    activeGroup = activeGroup,
    groupChilds = groupChilds,
    currentMedia = currentMedia?.let {
        NowPlaying(
            queueId = it.queueId,
            title = it.title ?: "",
            artist = it.artist ?: "",
            album = it.album ?: "",
            imageUrl = queueTrackImageUrl
                ?: it.imageUrl
                ?: it.image?.path?.let { path -> wsClient.getImageUrl(path) },
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
                    imageUrl = mi.resolveImageUrl(wsClient)
                        ?: mi.album?.resolveImageUrl(wsClient),
                    favorite = mi.favorite,
                    artistItemId = mi.artists?.firstOrNull()?.itemId,
                    artistProvider = mi.artists?.firstOrNull()?.provider,
                    albumItemId = mi.album?.itemId,
                    albumProvider = mi.album?.provider,
                    artistUri = mi.artists?.firstOrNull()?.uri,
                    albumUri = mi.album?.uri,
                    genres = mi.metadata?.genres ?: emptyList(),
                    year = mi.album?.year ?: mi.year
                )
            },
            imageUrl = item.mediaItem?.resolveImageUrl(wsClient)
                ?: item.image?.let { if (it.remotelyAccessible) it.path else wsClient.getImageUrl(it.path) }
        )
    }
)
