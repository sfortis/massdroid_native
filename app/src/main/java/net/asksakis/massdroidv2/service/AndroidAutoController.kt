package net.asksakis.massdroidv2.service

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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.auto.AaMetrics
import net.asksakis.massdroidv2.auto.AaProjectionObserver
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlaybackPosition
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.ui.MainActivity
import okhttp3.Request

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
) {
    private var session: MediaLibraryService.MediaLibrarySession? = null
    private var remotePlayer: RemoteControlPlayer? = null
    private var aaProjectionObserver: AaProjectionObserver? = null
    private val isAaProjecting = kotlinx.coroutines.flow.MutableStateFlow(false)

    private var cachedArtworkUrl: String? = null
    private var cachedArtworkKey: String? = null
    @Volatile private var cachedArtworkData: ByteArray? = null
    @Volatile private var lastPlaybackSnapshot: AutoPlaybackSnapshot = AutoPlaybackSnapshot.Empty
    private var optimisticVolume: Int? = null
    private var volumeOverrideUntil: Long = 0

    fun start(libraryCallback: MediaLibraryService.MediaLibrarySession.Callback) {
        remotePlayer = createRemotePlayer()
        createMediaSession(libraryCallback)
        observeProjection()
        observePlayerLock()
        observePlayback()
        observePosition()
        observeQueueItems()
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
        maybeAutoSelectSendspin(reason)
    }

    fun onSendspinActive(reason: String) {
        maybeAutoSelectSendspin(reason)
    }

    private fun observeProjection() {
        val observer = AaProjectionObserver(service.applicationContext)
        aaProjectionObserver = observer
        scope.launch {
            observer.isProjecting.collect { projecting ->
                isAaProjecting.value = projecting
                if (projecting) maybeAutoSelectSendspin("projection_started")
            }
        }
    }

    private fun observePlayerLock() {
        scope.launch {
            playerRepository.selectedPlayer.collect { selected ->
                if (selected?.playerId != sendspinPlayerId()) {
                    maybeAutoSelectSendspin("selected_player_changed")
                }
            }
        }
    }

    private fun maybeAutoSelectSendspin(reason: String) {
        if (!isAaProjecting.value) return
        val target = sendspinPlayerId() ?: return
        if (!isSendspinActive()) {
            Log.d(TAG, "AA auto-route ($reason): Sendspin target known but not active yet")
            return
        }
        val currentSelected = playerRepository.selectedPlayer.value?.playerId
        if (currentSelected != target) {
            Log.d(TAG, "AA auto-route ($reason): selecting Sendspin player $target (was $currentSelected)")
            playerRepository.selectPlayer(target)
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
        return if (System.currentTimeMillis() < volumeOverrideUntil) {
            optimisticVolume ?: serverVolume
        } else {
            serverVolume
        }
    }

    private fun pushVolume(playerId: String, volume: Int) {
        optimisticVolume = volume
        volumeOverrideUntil = System.currentTimeMillis() + VOLUME_OVERRIDE_MS
        val updated = lastPlaybackSnapshot.copy(volumeLevel = volume)
        lastPlaybackSnapshot = updated
        remotePlayer?.updatePlayback(updated, currentPositionMs(updated))
        sendVolumeCommand(playerId, volume)
    }

    private fun playQueueIndex(index: Int, reason: String) {
        val queueState = playerRepository.queueState.value ?: return
        if (index < 0) return
        if (isSendspinActive() && playerRepository.selectedPlayer.value?.playerId == sendspinPlayerId()) {
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

    private fun observePlayback() {
        scope.launch {
            val structuralQueue = playerRepository.queueState.distinctUntilChanged { a, b ->
                a?.queueId == b?.queueId &&
                    a?.shuffleEnabled == b?.shuffleEnabled &&
                    a?.repeatMode == b?.repeatMode &&
                    a?.currentIndex == b?.currentIndex &&
                    a?.currentItem?.queueItemId == b?.currentItem?.queueItemId &&
                    a?.currentItem?.track?.uri == b?.currentItem?.track?.uri
            }
            combine(playerRepository.selectedPlayer, structuralQueue) { player, queue ->
                player to queue
            }.collect { (player, queue) ->
                if (player == null) return@collect
                if (player.state != PlaybackState.PLAYING) onPlaybackStopped("playback-stopped")

                val currentTrack = queue?.currentItem?.track
                val trackUri = currentTrack?.uri ?: player.currentMedia?.uri
                val currentIndex = queue?.currentIndex ?: 0
                val snapshot = AutoPlaybackSnapshot(
                    isPlaying = player.state == PlaybackState.PLAYING,
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
                    artworkData = cachedArtworkData,
                    volumeLevel = effectiveVolume(player.volumeLevel),
                    isMuted = player.volumeMuted,
                    isRemotePlayback = player.playerId != sendspinPlayerId(),
                )
                val previous = lastPlaybackSnapshot
                val trackChanged = previous.trackUri != null &&
                    (previous.trackUri != snapshot.trackUri || previous.currentIndex != snapshot.currentIndex)
                updateArtwork(
                    currentTrack?.imageUrl ?: queue?.currentItem?.imageUrl ?: player.currentMedia?.imageUrl
                )
                lastPlaybackSnapshot = snapshot
                val positionMs = if (trackChanged) 0L else currentPositionMs(snapshot)
                AaMetrics.traceUpdateState(
                    positionMs = positionMs,
                    isPlaying = snapshot.isPlaying,
                    title = snapshot.title,
                    queueId = queue?.queueId
                )
                remotePlayer?.updatePlayback(snapshot, positionMs)
            }
        }
    }

    private fun effectiveVolume(serverVolume: Int): Int {
        val optimistic = optimisticVolume ?: return serverVolume
        return if (System.currentTimeMillis() > volumeOverrideUntil) {
            optimisticVolume = null
            serverVolume
        } else {
            optimistic
        }
    }

    private fun observePosition() {
        scope.launch {
            playerRepository.playbackPosition.collect { position ->
                if (position == null || !position.matches(lastPlaybackSnapshot)) return@collect
                remotePlayer?.syncPosition((position.position * 1000).toLong())
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

    private fun observeQueueItems() {
        scope.launch {
            playerRepository.queueItemsChanged.collectLatest { queueId ->
                fetchQueueItems(queueId)
            }
        }
        scope.launch {
            playerRepository.queueState
                .map { it?.queueId }
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { queueId -> fetchQueueItems(queueId) }
        }
    }

    private suspend fun fetchQueueItems(queueId: String) {
        try {
            val items = withContext(Dispatchers.IO) { musicRepository.getQueueItems(queueId) }
            if (queueId != playerRepository.queueState.value?.queueId) {
                Log.d(TAG, "Dropping stale queue items for $queueId")
                return
            }
            val entries = items.map { qi ->
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
            remotePlayer?.updateQueue(AutoQueueSnapshot(queueId = queueId, entries = entries))
            AaMetrics.traceUpdateQueue(queueId, entries.size)
            Log.d(TAG, "Queue updated: ${entries.size} items for $queueId")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queue items for $queueId", e)
        }
    }

    private fun updateArtwork(imageUrl: String?) {
        val artworkKey = imageUrl?.toArtworkKey()
        if (imageUrl != null && artworkKey != cachedArtworkKey) {
            cachedArtworkUrl = imageUrl
            cachedArtworkKey = artworkKey
            cachedArtworkData = null
            scope.launch(Dispatchers.IO) { downloadArtwork(imageUrl) }
        } else if (imageUrl == null && cachedArtworkKey != null) {
            cachedArtworkUrl = null
            cachedArtworkKey = null
            cachedArtworkData = null
            pushArtwork(null)
        }
    }

    private fun downloadArtwork(url: String) {
        try {
            val request = Request.Builder().url(url).build()
            val rawBytes = wsClient.getImageClient().newCall(request).execute().use { response ->
                response.body?.bytes()
            }
            if (url != cachedArtworkUrl || rawBytes == null || rawBytes.isEmpty()) return
            val resized = resizeArtwork(rawBytes)
            val currentData = cachedArtworkData
            if (currentData != null && resized.contentEquals(currentData)) return
            if (url != cachedArtworkUrl) return
            cachedArtworkData = resized
            scope.launch {
                if (url == cachedArtworkUrl) pushArtwork(resized)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Artwork download failed: $url", e)
        }
    }

    private fun pushArtwork(data: ByteArray?) {
        val updated = lastPlaybackSnapshot.copy(artworkData = data)
        lastPlaybackSnapshot = updated
        remotePlayer?.updatePlayback(updated, currentPositionMs(updated))
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
        private const val VOLUME_STEP = RemoteControlPlayer.VOLUME_SCALE
        private const val VOLUME_OVERRIDE_MS = 15_000L
    }
}
