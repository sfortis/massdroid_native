package net.asksakis.massdroidv2.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.proximity.DetectedRoom
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository

class ProximityPlaybackController(
    private val service: PlaybackService,
    private val scope: CoroutineScope,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val proximityConfigStore: ProximityConfigStore,
    private val sendVolumeCommand: (playerId: String, volume: Int) -> Unit,
    private val onReevaluate: () -> Unit,
) {
    companion object {
        private const val TAG = "ProximityPlayback"
        private const val PROXIMITY_CHANNEL_ID = "massdroid_proximity_v2"
        private const val PROXIMITY_NOTIFICATION_ID = 4
        private const val PROXIMITY_TRANSFER_ACTION = "net.asksakis.massdroidv2.PROXIMITY_TRANSFER"
        private const val PROXIMITY_PLAY_ACTION = "net.asksakis.massdroidv2.PROXIMITY_PLAY"
        private const val PROXIMITY_REEVALUATE_ACTION = "net.asksakis.massdroidv2.PROXIMITY_REEVALUATE"
        private const val PROXIMITY_COMMAND_DEDUPE_MS = 12_000L
    }

    private var pendingProximityTransfer: DetectedRoom? = null
    private var pendingTransferSourcePlayerId: String? = null
    private var lastProximityTransferCommand: RecentProximityTransferCommand? = null
    private var lastRoomPlaybackCommand: RecentRoomPlaybackCommand? = null

    fun handleStartCommand(intent: Intent?): Boolean {
        when (intent?.action) {
            PROXIMITY_TRANSFER_ACTION -> {
                val room = pendingProximityTransfer ?: return false
                val sourceId = pendingTransferSourcePlayerId
                clearPending()
                cancelNotification()
                if (sourceId != null) performProximityTransfer(sourceId, room)
                return true
            }
            PROXIMITY_PLAY_ACTION -> {
                val room = pendingProximityTransfer ?: return false
                val roomConfig = proximityConfigStore.config.value.rooms.find { it.id == room.roomId }
                clearPending()
                cancelNotification()
                scope.launch {
                    try {
                        playerRepository.selectPlayer(room.playerId)
                        val queueItems = try {
                            musicRepository.getQueueItems(room.playerId, limit = 1)
                        } catch (_: Exception) {
                            emptyList()
                        }
                        if (queueItems.isNotEmpty()) {
                            playerRepository.play(room.playerId)
                            applyRoomVolume(room)
                            Log.d(TAG, "Proximity play on: ${room.playerName}")
                        } else {
                            performRoomPlayback(room, roomConfig)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Proximity play failed: ${e.message}")
                    }
                }
                return true
            }
            PROXIMITY_REEVALUATE_ACTION -> {
                onReevaluate()
                return true
            }
        }
        return false
    }

    fun clearPending() {
        pendingProximityTransfer = null
        pendingTransferSourcePlayerId = null
    }

    fun reset() {
        clearPending()
        lastProximityTransferCommand = null
        lastRoomPlaybackCommand = null
        cancelNotification()
    }

    fun isPendingRoom(roomId: String): Boolean =
        pendingProximityTransfer?.roomId == roomId

    fun showRoomDetectedNotification(room: DetectedRoom) {
        val notification = NotificationCompat.Builder(service, PROXIMITY_CHANNEL_ID)
            .setContentTitle("Now in ${room.roomName}")
            .setContentText("Follow Me detected ${room.roomName}")
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setTimeoutAfter(10_000)
            .build()
        notificationManager()?.notify(PROXIMITY_NOTIFICATION_ID, notification)
    }

    fun showActionNotification(room: DetectedRoom, canTransfer: Boolean, sourcePlayerId: String?) {
        pendingProximityTransfer = room
        pendingTransferSourcePlayerId = sourcePlayerId
        val transferIntent = Intent(PROXIMITY_TRANSFER_ACTION, null, service, PlaybackService::class.java)
        val transferPending = PendingIntent.getService(
            service, 0, transferIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playIntent = Intent(PROXIMITY_PLAY_ACTION, null, service, PlaybackService::class.java)
        val playPending = PendingIntent.getService(
            service, 1, playIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val actionLabel = if (canTransfer) "Move Here" else "Play Here"

        val notification = NotificationCompat.Builder(service, PROXIMITY_CHANNEL_ID)
            .setContentTitle("You're near ${room.roomName}")
            .setContentText("$actionLabel on ${room.playerName}?")
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(R.drawable.ic_notification, actionLabel, if (canTransfer) transferPending else playPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setTimeoutAfter(120_000)
            .build()
        notificationManager()?.notify(PROXIMITY_NOTIFICATION_ID, notification)
    }

    fun performProximityTransfer(sourcePlayerId: String, room: DetectedRoom) {
        if (sourcePlayerId == room.playerId) return
        if (!isPlayerAvailable(room.playerId)) {
            Log.w(TAG, "Proximity transfer skipped: ${room.playerName} not available")
            return
        }
        if (shouldSkipRecentTransfer(sourcePlayerId, room)) {
            Log.d(TAG, "Proximity transfer skipped: duplicate recent move $sourcePlayerId -> ${room.playerId} (${room.roomName})")
            return
        }
        rememberTransferCommand(sourcePlayerId, room)
        scope.launch {
            try {
                musicRepository.transferQueue(sourcePlayerId, room.playerId)
                playerRepository.selectPlayer(room.playerId)
                applyRoomVolume(room)
                Log.d(TAG, "Proximity transfer: $sourcePlayerId -> ${room.playerId}")
            } catch (e: Exception) {
                Log.w(TAG, "Proximity transfer failed: ${e.message}")
            }
        }
    }

    fun applyRoomVolume(room: DetectedRoom) {
        if (!isPlayerAvailable(room.playerId)) return
        val roomConfig = proximityConfigStore.config.value.rooms.find { it.id == room.roomId } ?: return
        val playback = roomConfig.playbackConfig
        if (!playback.volumeEnabled) return
        val volume = (playback.volumeLevel * 10).coerceIn(0, 100)
        sendVolumeCommand(room.playerId, volume)
        Log.d(TAG, "Proximity volume: ${room.playerName} -> $volume%")
    }

    fun isPlayerAvailable(playerId: String): Boolean {
        return playerRepository.players.value.any { it.playerId == playerId && it.available }
    }

    fun resolvePlaybackContext(targetPlayerId: String): ProximityPlaybackContext {
        val players = playerRepository.players.value.filter { it.available }
        val targetPlayer = players.find { it.playerId == targetPlayerId }
        val selectedPlayer = playerRepository.selectedPlayer.value
        val selectedAvailablePlayer = selectedPlayer?.let { selected ->
            players.find { it.playerId == selected.playerId }
        }
        val playingPlayers = players.filter { it.state == PlaybackState.PLAYING }
        val transferCandidates = playingPlayers.filter { it.playerId != targetPlayerId }

        val resolvedTransferSource = when {
            transferCandidates.size == 1 -> transferCandidates.first()
            transferCandidates.isEmpty() &&
                selectedAvailablePlayer != null &&
                selectedAvailablePlayer.playerId != targetPlayerId &&
                selectedAvailablePlayer.state == PlaybackState.PLAYING -> selectedAvailablePlayer
            else -> null
        }

        return ProximityPlaybackContext(
            targetPlayer = targetPlayer,
            transferSourcePlayer = resolvedTransferSource,
            ambiguousTransferPlayers = if (resolvedTransferSource == null && transferCandidates.size > 1) {
                transferCandidates
            } else {
                emptyList()
            }
        )
    }

    private suspend fun performRoomPlayback(
        room: DetectedRoom,
        roomConfig: net.asksakis.massdroidv2.data.proximity.RoomConfig?
    ) {
        if (!isPlayerAvailable(room.playerId)) {
            Log.w(TAG, "Proximity play skipped: ${room.playerName} not available")
            return
        }
        val playback = roomConfig?.playbackConfig
        val uri = playback?.playlistUri
        if (uri != null) {
            if (shouldSkipRecentRoomPlayback(room, uri)) {
                Log.d(TAG, "Proximity room playback skipped: duplicate recent command on ${room.playerName} for $uri")
                return
            }
            rememberRoomPlaybackCommand(room, uri)
            musicRepository.playMedia(room.playerId, uri, option = "replace")
            if (playback.shuffle) {
                try {
                    musicRepository.shuffleQueue(room.playerId, true)
                } catch (_: Exception) {
                }
            }
            applyRoomVolume(room)
            Log.d(TAG, "Proximity: playing '${playback.playlistName}' on ${room.playerName} (shuffle=${playback.shuffle})")
        } else {
            Log.d(TAG, "Proximity play: no queue or playlist on ${room.playerName}")
            android.widget.Toast.makeText(service, "No queue on ${room.playerName}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun shouldSkipRecentTransfer(sourcePlayerId: String, room: DetectedRoom): Boolean {
        val recent = lastProximityTransferCommand ?: return false
        val now = System.currentTimeMillis()
        return now - recent.issuedAtMs <= PROXIMITY_COMMAND_DEDUPE_MS &&
            recent.sourcePlayerId == sourcePlayerId &&
            recent.targetPlayerId == room.playerId &&
            recent.roomId == room.roomId
    }

    private fun rememberTransferCommand(sourcePlayerId: String, room: DetectedRoom) {
        lastProximityTransferCommand = RecentProximityTransferCommand(
            sourcePlayerId = sourcePlayerId,
            targetPlayerId = room.playerId,
            roomId = room.roomId,
            issuedAtMs = System.currentTimeMillis()
        )
    }

    private fun shouldSkipRecentRoomPlayback(room: DetectedRoom, playlistUri: String): Boolean {
        val recent = lastRoomPlaybackCommand ?: return false
        val now = System.currentTimeMillis()
        return now - recent.issuedAtMs <= PROXIMITY_COMMAND_DEDUPE_MS &&
            recent.playerId == room.playerId &&
            recent.roomId == room.roomId &&
            recent.playlistUri == playlistUri
    }

    private fun rememberRoomPlaybackCommand(room: DetectedRoom, playlistUri: String) {
        lastRoomPlaybackCommand = RecentRoomPlaybackCommand(
            playerId = room.playerId,
            roomId = room.roomId,
            playlistUri = playlistUri,
            issuedAtMs = System.currentTimeMillis()
        )
    }

    private fun cancelNotification() {
        notificationManager()?.cancel(PROXIMITY_NOTIFICATION_ID)
    }

    private fun notificationManager(): NotificationManager? =
        service.getSystemService(NotificationManager::class.java)

    data class ProximityPlaybackContext(
        val targetPlayer: net.asksakis.massdroidv2.domain.model.Player?,
        val transferSourcePlayer: net.asksakis.massdroidv2.domain.model.Player?,
        val ambiguousTransferPlayers: List<net.asksakis.massdroidv2.domain.model.Player>
    )

    private data class RecentProximityTransferCommand(
        val sourcePlayerId: String,
        val targetPlayerId: String,
        val roomId: String,
        val issuedAtMs: Long
    )

    private data class RecentRoomPlaybackCommand(
        val playerId: String,
        val roomId: String,
        val playlistUri: String,
        val issuedAtMs: Long
    )
}
