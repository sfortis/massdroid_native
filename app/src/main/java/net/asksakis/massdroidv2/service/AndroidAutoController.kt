package net.asksakis.massdroidv2.service

import net.asksakis.massdroidv2.playback.SendspinAudioController

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.auto.AaMetrics
import net.asksakis.massdroidv2.auto.AaProjectionObserver
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.sendspin.SyncState
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlaybackPosition
import net.asksakis.massdroidv2.domain.repository.PlayerSelectionLock
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.ui.MainActivity
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AndroidAutoController(
    private val service: MediaLibraryService,
    private val scope: CoroutineScope,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val wsClient: MaWebSocketClient,
    private val sendspinManager: SendspinManager,
    private val sendspinPlayerId: () -> String?,
    private val isSendspinActive: () -> Boolean,
    private val sendspinController: () -> SendspinAudioController?,
    private val shouldRouteToSendspin: () -> Boolean,
    private val activePlayerId: () -> String?,
    private val sendVolumeCommand: (String, Int) -> Unit,
    private val onPlaybackStopped: (String) -> Unit,
    private val trackedBrowsePaths: () -> Set<String>,
) {
    private var session: MediaLibraryService.MediaLibrarySession? = null
    private var remotePlayer: RemoteControlPlayer? = null
    private var aaProjectionObserver: AaProjectionObserver? = null
    private val isAaProjecting = MutableStateFlow(false)

    // Source-of-truth flows that feed the master combine. All side-channel
    // updates (artwork download, queue fetch, AA-initiated volume tap) write
    // into these instead of pushing to remotePlayer directly. The single
    // master observer in observeAaState() consumes them coherently.
    private val artworkData = MutableStateFlow<ByteArray?>(null)
    private val queueSnapshot = MutableStateFlow(AutoQueueSnapshot.Empty)
    /**
     * AA-originated optimistic volume bump: (volume, expiresAtMs). The
     * combine prefers this value over the server-reported volume until
     * expiresAtMs has elapsed, so the AA slider doesn't snap back during the
     * MA round-trip.
     */
    private val volumeOverride = MutableStateFlow<Pair<Int, Long>?>(null)

    private var cachedArtworkUrl: String? = null
    private var cachedArtworkKey: String? = null
    // The last AaCompleteState we applied, so the master observer can diff
    // and dispatch only the channel that actually changed (playback / queue /
    // media buttons). Mutated only inside the combine collector — single
    // coroutine, no synchronization needed.
    private var lastAaState: AaCompleteState? = null
    @Volatile private var lastPlaybackSnapshot: AutoPlaybackSnapshot = AutoPlaybackSnapshot.Empty

    fun start(libraryCallback: MediaLibraryService.MediaLibrarySession.Callback) {
        remotePlayer = createRemotePlayer()
        createMediaSession(libraryCallback)
        observeProjection()
        observeAaState()
        observePosition()
        observeServerPositionUpdates()
        observeQueueItems()
        observeWsForBrowseInvalidation()
    }

    fun getSession(): MediaLibraryService.MediaLibrarySession? = session

    fun stop() {
        remotePlayer?.release()
        remotePlayer = null
        session?.release()
        session = null
        aaProjectionObserver = null
    }

    fun onSendspinTargetChanged(reason: String) {
        updateSendspinSelectionLock(reason)
    }

    fun onSendspinActive(reason: String) {
        updateSendspinSelectionLock(reason)
    }

    fun onSendspinInactive(reason: String) {
        updateSendspinSelectionLock(reason)
    }

    fun handleCustomCommand(action: String): Boolean {
        return when (action) {
            AndroidAutoMediaCommands.ACTION_TOGGLE_FAVORITE -> {
                toggleFavorite()
                true
            }
            AndroidAutoMediaCommands.ACTION_TOGGLE_SHUFFLE -> {
                toggleShuffle()
                true
            }
            else -> false
        }
    }

    private fun observeProjection() {
        val observer = AaProjectionObserver(service.applicationContext)
        aaProjectionObserver = observer
        scope.launch {
            observer.isProjecting.collect { projecting ->
                isAaProjecting.value = projecting
                updateSendspinSelectionLock(if (projecting) "projection_started" else "projection_stopped")
            }
        }
    }

    /**
     * When the WS connection transitions to Connected, fire notifyChildrenChanged
     * for every browse path the AA host has queried or subscribed to. This fixes
     * the cold-start race: AA binds and queries onGetChildren before the WS has
     * authed with MA, so musicRepository returns empty lists and AA caches them.
     * Without this re-broadcast, the user sees empty tabs until force-stop.
     *
     * Pattern: map to Connected-or-not, distinctUntilChanged, filter for the
     * positive edge only. Fires once per Disconnected/Error/Connecting → Connected
     * transition, including the initial transition when AA may have arrived before
     * the WS. Empty tracked-paths set is harmless (no notifies fired).
     */
    private fun observeWsForBrowseInvalidation() {
        scope.launch {
            wsClient.connectionState
                .map { it is ConnectionState.Connected }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    val paths = trackedBrowsePaths()
                    if (paths.isEmpty()) return@collect
                    val s = session ?: return@collect
                    Log.d(TAG, "WS connected, invalidating ${paths.size} AA browse paths")
                    paths.forEach { parentId ->
                        s.notifyChildrenChanged(parentId, Int.MAX_VALUE, null)
                    }
                }
        }
    }

    private fun updateSendspinSelectionLock(reason: String) {
        val target = sendspinPlayerId()
        val shouldLock = isAaProjecting.value && isSendspinActive() && target != null
        val currentLock = playerRepository.selectionLock.value
        when {
            shouldLock && currentLock?.playerId != target -> {
                playerRepository.setSelectionLock(PlayerSelectionLock(target, "android_auto:$reason"))
            }
            !shouldLock && currentLock?.reason?.startsWith("android_auto:") == true -> {
                playerRepository.setSelectionLock(null)
            }
        }
    }

    private fun createRemotePlayer(): RemoteControlPlayer {
        return RemoteControlPlayer(
            Looper.getMainLooper(),
            onPlay = {
                if (shouldRouteToSendspin()) {
                    sendspinController()?.handlePlay()
                } else {
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.play(id) }
                }
            },
            onPause = {
                if (shouldRouteToSendspin()) {
                    sendspinController()?.handlePause()
                } else {
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.pause(id) }
                }
            },
            onNext = {
                if (shouldRouteToSendspin()) {
                    sendspinController()?.handleNext()
                } else {
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.next(id) }
                }
            },
            onPrevious = {
                if (shouldRouteToSendspin()) {
                    sendspinController()?.handlePrev()
                } else {
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.previous(id) }
                }
            },
            onSeekToMediaItem = { mediaItemIndex ->
                playQueueIndex(mediaItemIndex, reason = "seek_to_media_item")
            },
            onSeek = { positionMs ->
                remotePlayer?.publishPosition(positionMs)
                val id = if (shouldRouteToSendspin()) sendspinPlayerId() else activePlayerId()
                id ?: return@RemoteControlPlayer
                scope.launch { playerRepository.seek(id, positionMs / 1000.0) }
            },
            onVolumeUp = {
                val player = playerRepository.selectedPlayer.value ?: return@RemoteControlPlayer
                val newVolume = (currentVolumeBase(player.volumeLevel) + VOLUME_STEP)
                    .coerceAtMost(RemoteControlPlayer.MAX_VOLUME)
                pushVolume(player.playerId, newVolume)
            },
            onVolumeDown = {
                val player = playerRepository.selectedPlayer.value ?: return@RemoteControlPlayer
                val newVolume = (currentVolumeBase(player.volumeLevel) - VOLUME_STEP).coerceAtLeast(0)
                pushVolume(player.playerId, newVolume)
            },
            onVolumeSet = { volume ->
                val player = playerRepository.selectedPlayer.value ?: return@RemoteControlPlayer
                pushVolume(player.playerId, volume.coerceIn(0, RemoteControlPlayer.MAX_VOLUME))
            }
        )
    }

    private fun currentVolumeBase(serverVolume: Int): Int {
        val override = volumeOverride.value ?: return serverVolume
        return if (System.currentTimeMillis() < override.second) override.first else serverVolume
    }

    private fun pushVolume(playerId: String, volume: Int) {
        // Optimistic AA-side update: bump the override flow so the master
        // combine emits with the new value and AA sees its volume slider
        // settle immediately. The actual MA echo arrives via PLAYER_UPDATED
        // and overwrites the override once it expires.
        volumeOverride.value = volume to (System.currentTimeMillis() + VOLUME_OVERRIDE_MS)
        sendVolumeCommand(playerId, volume)
    }

    private fun playQueueIndex(index: Int, reason: String) {
        if (index < 0) return
        // An audiobook is ONE queue item; its AA "queue rows" are chapters, so a
        // row tap is a chapter selection, not a queue-index jump. A raw
        // play_media index on the single item bypasses the discontinuity signal
        // and wedges the Sendspin gate (same class as the next/previous freeze).
        // Route it to a seek (which emits the SEEK discontinuity itself and gets
        // a fresh stream), consistent with PlayerRepositoryImpl.audiobookChapterSkip.
        val track = playerRepository.queueState.value?.currentItem?.track
        if (track?.mediaType == MediaType.AUDIOBOOK) {
            val target = track.chapters.getOrNull(index) ?: return
            val id = if (shouldRouteToSendspin()) sendspinPlayerId() else activePlayerId()
            id ?: return
            scope.launch {
                try {
                    playerRepository.seek(id, target.start)
                } catch (e: Exception) {
                    Log.e(TAG, "AA chapter seek failed: index=$index start=${target.start}", e)
                }
            }
            return
        }
        val queueState = playerRepository.queueState.value ?: return
        // Warn Sendspin of the discontinuity whenever it is the active output
        // (directly selected OR BT-routed), not only when it is the selected
        // player — otherwise a routed queue jump leaves the gate un-warned.
        if (isSendspinActive() &&
            (shouldRouteToSendspin() || playerRepository.selectedPlayer.value?.playerId == sendspinPlayerId())
        ) {
            sendspinManager.expectDiscontinuity(reason)
        }
        scope.launch(Dispatchers.IO) {
            try {
                musicRepository.playQueueIndex(queueState.queueId, index)
            } catch (e: Exception) {
                Log.e(TAG, "AA playQueueIndex failed: queue=${queueState.queueId} index=$index", e)
            }
        }
    }

    private fun createMediaSession(libraryCallback: MediaLibraryService.MediaLibrarySession.Callback) {
        val pendingIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibraryService.MediaLibrarySession.Builder(service, remotePlayer!!, libraryCallback)
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(SyncBitmapLoader())
            .setPeriodicPositionUpdateEnabled(false)
            .build()
        Log.d(TAG, "MediaLibrarySession created")
    }

    /**
     * Single source-of-truth observer for the AA host state. Combines every
     * input that affects what AA sees (selected player, queue structure,
     * Sendspin engine transitions, artwork, queue items list, optimistic
     * volume) into one coherent AaCompleteState. The collector diffs against
     * the previously applied state and dispatches only the channel that
     * actually changed: updatePlayback / updateQueue / setMediaButtonPreferences.
     *
     * Why a single observer: each side-channel push path used to produce its
     * own AA invalidate. On a track change three independent observers all
     * fired within ~100ms, triggering three gearhead state re-queries with
     * partially-stale snapshots and occasionally racing against artwork that
     * arrived asynchronously. Funnelling everything through one combine makes
     * artwork updates ride the same coroutine as state updates, naturally
     * coalesces simultaneous changes via distinctUntilChanged, and keeps
     * lastAaState consistent under a single mutator.
     *
     * Position is intentionally not part of this state — it changes at 2 Hz
     * via the local ticker and needs a different push policy (silent sync for
     * the ticker, invalidate for server-confirmed updates). It rides its own
     * dedicated observers below.
     */
    private fun observeAaState() {
        scope.launch {
            val structuralQueue = playerRepository.queueState.distinctUntilChanged { a, b ->
                a?.queueId == b?.queueId &&
                    a?.shuffleEnabled == b?.shuffleEnabled &&
                    a?.repeatMode == b?.repeatMode &&
                    a?.currentIndex == b?.currentIndex &&
                    a?.currentItem?.queueItemId == b?.currentItem?.queueItemId &&
                    a?.currentItem?.track?.uri == b?.currentItem?.track?.uri
            }
            combine(
                playerRepository.selectedPlayer,
                structuralQueue,
                sendspinManager.connectionState,
                sendspinManager.syncState,
                artworkData,
                queueSnapshot,
                volumeOverride,
            ) { values: Array<*> ->
                @Suppress("UNCHECKED_CAST")
                buildAaState(
                    player = values[0] as net.asksakis.massdroidv2.domain.model.Player?,
                    queue = values[1] as net.asksakis.massdroidv2.domain.model.QueueState?,
                    ssConn = values[2] as SendspinState,
                    ssSync = values[3] as SyncState,
                    artwork = values[4] as ByteArray?,
                    queueEntries = values[5] as AutoQueueSnapshot,
                    volumeOv = values[6] as Pair<Int, Long>?,
                )
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { newState ->
                    if (!newState.playback.isPlaying) onPlaybackStopped("playback-stopped")
                    // Side-effect: start an artwork download when the source
                    // image URL changes. This writes back into artworkData,
                    // which re-enters the combine.
                    updateArtwork(newState.imageUrl)
                    applyAaState(lastAaState, newState)
                    lastAaState = newState
                    lastPlaybackSnapshot = newState.playback
                    clearExpiredVolumeOverride()
                }
        }
    }

    private fun buildAaState(
        player: net.asksakis.massdroidv2.domain.model.Player?,
        queue: net.asksakis.massdroidv2.domain.model.QueueState?,
        ssConn: SendspinState,
        ssSync: SyncState,
        artwork: ByteArray?,
        queueEntries: AutoQueueSnapshot,
        volumeOv: Pair<Int, Long>?,
    ): AaCompleteState? {
        player ?: return null
        val currentTrack = queue?.currentItem?.track
        val trackUri = currentTrack?.uri ?: player.currentMedia?.uri
        val currentIndex = queue?.currentIndex ?: 0
        val isSendspinSelected = player.playerId == sendspinPlayerId()
        // Audio actually flowing: for Sendspin we have engine-level truth
        // (STREAMING + (SYNCHRONIZED || HOLDOVER)); for remote MA players we
        // trust the server-reported PLAYING state. STATE_BUFFERING in
        // RemoteControlPlayer.getState() reads this to pause the AA bar
        // during seeks / track-changes / sync transitions / network blips.
        val audioFlowing = if (isSendspinSelected) {
            ssConn == SendspinState.STREAMING &&
                (ssSync == SyncState.SYNCHRONIZED ||
                    ssSync == SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
        } else {
            player.state == PlaybackState.PLAYING
        }
        val volumeLevel = effectiveVolume(player.volumeLevel, volumeOv)
        val playback = AutoPlaybackSnapshot(
            isPlaying = player.state == PlaybackState.PLAYING,
            audioFlowing = audioFlowing,
            queueItemId = queue?.currentItem?.queueItemId,
            trackUri = trackUri,
            title = currentTrack?.name ?: player.currentMedia?.title ?: "",
            artist = currentTrack?.artistNames ?: player.currentMedia?.artist ?: "",
            album = currentTrack?.albumName ?: player.currentMedia?.album ?: "",
            durationMs = ((currentTrack?.duration
                ?: queue?.currentItem?.duration
                ?: player.currentMedia?.duration
                ?: 0.0) * 1000).toLong(),
            currentIndex = currentIndex,
            artworkData = artwork,
            volumeLevel = volumeLevel,
            isMuted = player.volumeMuted,
            isRemotePlayback = !isSendspinSelected,
            chapters = if (currentTrack?.mediaType == MediaType.AUDIOBOOK) currentTrack.chapters else emptyList(),
        )
        return AaCompleteState(
            playback = playback,
            queue = queueEntries,
            isFavorite = currentTrack?.favorite == true,
            shuffleEnabled = queue?.shuffleEnabled == true,
            imageUrl = currentTrack?.imageUrl ?: queue?.currentItem?.imageUrl ?: player.currentMedia?.imageUrl,
        )
    }

    private fun applyAaState(prev: AaCompleteState?, new: AaCompleteState) {
        val rp = remotePlayer ?: return
        val playbackChanged = prev?.playback != new.playback
        val trackChanged = prev?.playback?.trackUri != null &&
            (prev.playback.trackUri != new.playback.trackUri ||
                prev.playback.currentIndex != new.playback.currentIndex)
        if (playbackChanged) {
            val positionMs = if (trackChanged) 0L else currentPositionMs(new.playback)
            AaMetrics.traceUpdateState(
                positionMs = positionMs,
                isPlaying = new.playback.isPlaying,
                title = new.playback.title,
                queueId = new.queue.queueId,
            )
            rp.updatePlayback(new.playback, positionMs)
        }
        if (prev?.queue != new.queue) {
            rp.updateQueue(new.queue)
        }
        if (prev?.isFavorite != new.isFavorite || prev?.shuffleEnabled != new.shuffleEnabled) {
            session?.setMediaButtonPreferences(
                AndroidAutoMediaCommands.buttons(service, new.isFavorite, new.shuffleEnabled)
            )
        }
    }

    private fun effectiveVolume(serverVolume: Int, override: Pair<Int, Long>?): Int {
        if (override == null) return serverVolume
        // Pure read: an expired override is simply ignored. Clearing it is a side
        // effect that belongs in the collector (clearExpiredVolumeOverride), not
        // in this combine transform.
        if (System.currentTimeMillis() > override.second) return serverVolume
        return override.first
    }

    /** Clear an expired optimistic volume override so the combine re-settles to
     *  the server value. Runs in the state collector, never inside the mapper. */
    private fun clearExpiredVolumeOverride() {
        val ov = volumeOverride.value ?: return
        if (System.currentTimeMillis() > ov.second) volumeOverride.value = null
    }

    private fun toggleFavorite() {
        val track = playerRepository.queueState.value?.currentItem?.track ?: return
        val newFavorite = !track.favorite
        // Optimistic state push: update the source-of-truth favorite flag in
        // the repository — the master combine reads queueState.currentItem
        // and re-emits AaCompleteState with the new favorite, which flips the
        // media buttons via applyAaState. No direct session push from here.
        playerRepository.updateCurrentTrackFavorite(newFavorite)
        scope.launch(Dispatchers.IO) {
            try {
                musicRepository.setFavorite(track.uri, MediaType.TRACK, track.itemId, newFavorite)
            } catch (e: Exception) {
                Log.w(TAG, "AA favorite toggle failed: ${e.message}")
                if (playerRepository.queueState.value?.currentItem?.track?.uri == track.uri) {
                    playerRepository.updateCurrentTrackFavorite(track.favorite)
                }
            }
        }
    }

    private fun toggleShuffle() {
        val queue = playerRepository.queueState.value ?: return
        val enabled = !queue.shuffleEnabled
        // Optimistic shuffle: master combine watches structuralQueue (which
        // includes shuffleEnabled). MA echo via QUEUE_UPDATED arrives shortly
        // and confirms / corrects. No direct session push here.
        scope.launch(Dispatchers.IO) {
            try {
                musicRepository.shuffleQueue(queue.queueId, enabled)
            } catch (e: Exception) {
                Log.w(TAG, "AA shuffle toggle failed: ${e.message}")
            }
        }
    }

    private fun observePosition() {
        scope.launch {
            playerRepository.playbackPosition.collect { position ->
                if (position == null || !position.matches(lastPlaybackSnapshot)) return@collect
                // Silent sync for every emission. This includes the 500ms local
                // interpolation ticker — we don't invalidate on these because
                // gearhead reacts to frequent invalidates by rebuilding the
                // queue (the original #20 regression). AA does its own 1x
                // interpolation; we just keep our internal positionMs aligned
                // so the next invalidate (from observeServerPositionUpdates or
                // observePlayback) carries the freshest value.
                remotePlayer?.syncPosition((position.position * 1000).toLong())
            }
        }
    }

    /**
     * Push AA invalidate on every server-confirmed position event
     * (QUEUE_TIME_UPDATED). These arrive only when MA actually broadcasts new
     * truth — seek, track-end clamp, normal forward progress (every 1-2s) —
     * not on the local 500ms interpolation ticker. That keeps the AA host's
     * progress bar synced to ground truth without flooding it with invalidates
     * that would re-trigger gearhead's queue rebuild.
     */
    private fun observeServerPositionUpdates() {
        scope.launch {
            playerRepository.serverPositionUpdates.collect { position ->
                if (!position.matches(lastPlaybackSnapshot)) return@collect
                remotePlayer?.publishPosition((position.position * 1000).toLong())
            }
        }
    }

    private fun currentPositionMs(snapshot: AutoPlaybackSnapshot): Long {
        val position = playerRepository.playbackPosition.value ?: return 0L
        if (!position.matches(snapshot)) return 0L
        return (position.position * 1000).toLong()
    }

    private fun PlaybackPosition.matches(snapshot: AutoPlaybackSnapshot): Boolean {
        val snapshotQueueItemId = snapshot.queueItemId
        if (!snapshotQueueItemId.isNullOrBlank() && !queueItemId.isNullOrBlank()) {
            return snapshotQueueItemId == queueItemId
        }
        val snapshotTrackUri = snapshot.trackUri
        if (!snapshotTrackUri.isNullOrBlank() && !trackUri.isNullOrBlank()) {
            return snapshotTrackUri == trackUri
        }
        return false
    }

    /**
     * Subscribe to the canonical queue-items snapshot owned by
     * `QueueItemsCoordinator` (exposed through
     * `PlayerRepository.queueItems`). The coordinator already debounces
     * the two upstream signals (`queueItemsChanged` and
     * `queueState.queueId`) and runs a single mutex-guarded
     * `player_queues/items` RPC per change, so AA just consumes that
     * shared result instead of issuing its own RPC.
     */
    private fun observeQueueItems() {
        scope.launch {
            playerRepository.queueItems
                .filterNotNull()
                .collectLatest { snapshot ->
                    if (snapshot.queueId != playerRepository.queueState.value?.queueId) {
                        Log.d(TAG, "Dropping stale queue items for ${snapshot.queueId}")
                        return@collectLatest
                    }
                    val entries = snapshot.items.map { qi ->
                        val art = qi.track?.imageUrl ?: qi.imageUrl
                        QueueEntry(
                            id = qi.queueItemId.toStableLongId(),
                            title = qi.track?.name ?: qi.name,
                            artist = qi.track?.artistNames ?: "",
                            album = qi.track?.albumName ?: "",
                            durationMs = ((qi.track?.duration ?: qi.duration) * 1000).toLong(),
                            artworkUri = art?.let { Uri.parse(it) },
                        )
                    }
                    // Feed the queue snapshot into the master combine.
                    // applyAaState diffs and calls remotePlayer.updateQueue
                    // only if the queue actually changed structurally.
                    queueSnapshot.value = AutoQueueSnapshot(queueId = snapshot.queueId, entries = entries)
                    AaMetrics.traceUpdateQueue(snapshot.queueId, entries.size)
                    Log.d(TAG, "Queue updated: ${entries.size} items for ${snapshot.queueId}")
                }
        }
    }

    private fun updateArtwork(imageUrl: String?) {
        val artworkKey = imageUrl?.toArtworkKey()
        if (imageUrl != null && artworkKey != cachedArtworkKey) {
            cachedArtworkUrl = imageUrl
            cachedArtworkKey = artworkKey
            artworkData.value = null
            scope.launch(Dispatchers.IO) { downloadArtwork(imageUrl) }
        } else if (imageUrl == null && cachedArtworkKey != null) {
            cachedArtworkUrl = null
            cachedArtworkKey = null
            artworkData.value = null
        }
    }

    private fun downloadArtwork(url: String) {
        try {
            val request = Request.Builder().url(url).build()
            val call = wsClient.getImageClient().newCall(request).apply {
                // Bound the blocking fetch so a slow/stuck image server cannot pin
                // this IO thread indefinitely on a track change.
                timeout().timeout(ARTWORK_FETCH_TIMEOUT_S, TimeUnit.SECONDS)
            }
            val rawBytes = call.execute().use { response ->
                response.body?.bytes()
            }
            if (url != cachedArtworkUrl || rawBytes == null || rawBytes.isEmpty()) return
            val resized = resizeArtwork(rawBytes)
            val currentData = artworkData.value
            if (currentData != null && resized.contentEquals(currentData)) return
            if (url != cachedArtworkUrl) return
            // Feed artwork into the master combine. It re-emits with the new
            // bytes in AutoPlaybackSnapshot, applyAaState detects the change
            // (only the artwork differs) and calls updatePlayback once.
            artworkData.value = resized
        } catch (e: Exception) {
            Log.e(TAG, "Artwork download failed: $url", e)
        }
    }

    private fun String.toArtworkKey(): String {
        val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return this
        if (uri.lastPathSegment == "imageproxy") {
            uri.getQueryParameter("path")
                ?.decodeRepeatedly()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return this
    }

    private fun String.decodeRepeatedly(maxPasses: Int = 3): String {
        var value = this
        repeat(maxPasses) {
            val decoded = Uri.decode(value)
            if (decoded == value) return value
            value = decoded
        }
        return value
    }

    private fun resizeArtwork(rawBytes: ByteArray, maxSize: Int = 320): ByteArray {
        val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
        val scale = maxSize.toFloat() / maxOf(original.width, original.height)
        if (scale >= 1f) {
            original.recycle()
            return rawBytes
        }
        val scaled = Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt(),
            (original.height * scale).toInt(),
            true
        )
        original.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
        scaled.recycle()
        return out.toByteArray()
    }

    private companion object {
        private const val TAG = "AndroidAutoController"
        // MA-volume units added/removed per hardware-rocker press. Independent of
        // the device-volume mapping (now 1:1 with MA), so it can be tuned freely.
        private const val VOLUME_STEP = 3
        private const val VOLUME_OVERRIDE_MS = 15_000L
        private const val ARTWORK_FETCH_TIMEOUT_S = 8L
    }

    /**
     * The complete AA host state, computed coherently from all input flows
     * in observeAaState(). applyAaState() diffs successive emissions and
     * dispatches only the channel that actually changed.
     */
    private data class AaCompleteState(
        val playback: AutoPlaybackSnapshot,
        val queue: AutoQueueSnapshot,
        val isFavorite: Boolean,
        val shuffleEnabled: Boolean,
        /** Source image URL for the current track; used by updateArtwork(). */
        val imageUrl: String?,
    )
}
