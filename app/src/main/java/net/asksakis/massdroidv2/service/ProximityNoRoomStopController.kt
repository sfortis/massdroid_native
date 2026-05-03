package net.asksakis.massdroidv2.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.proximity.ProximityConfig
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.PlayerRepository

class ProximityNoRoomStopController(
    private val scope: CoroutineScope,
    private val playerRepository: PlayerRepository,
    private val proximityConfigStore: ProximityConfigStore,
    private val roomDetector: RoomDetector,
    private val shouldRunProximity: (ProximityConfig) -> Boolean,
    private val isWithinSchedule: () -> Boolean,
) {
    companion object {
        private const val TAG = "ProximityNoRoomStop"
        private const val NO_ROOM_STOP_TIMEOUT_MS = 10 * 60 * 1000L
    }

    private var roomActivityJob: Job? = null
    private var noRoomStopJob: Job? = null
    private var noRoomStopArmedPlayerId: String? = null

    fun start() {
        if (roomActivityJob?.isActive == true) return
        roomActivityJob = scope.launch {
            var previousRoomId: String? = null
            roomDetector.currentRoom.collect { room ->
                if (room != null) {
                    previousRoomId = room.roomId
                    cancel("room-active")
                } else {
                    if (previousRoomId != null) {
                        scheduleIfNeeded("room-lost", leftRoomId = previousRoomId)
                    }
                    previousRoomId = null
                }
            }
        }
    }

    fun stop() {
        roomActivityJob?.cancel()
        roomActivityJob = null
        cancel("engine-stopped")
    }

    fun cancel(reason: String) {
        if (noRoomStopJob?.isActive == true) {
            Log.d(TAG, "No-room stop timer canceled ($reason)")
        }
        noRoomStopJob?.cancel()
        noRoomStopJob = null
        noRoomStopArmedPlayerId = null
    }

    private fun scheduleIfNeeded(reason: String, leftRoomId: String? = null) {
        if (noRoomStopJob?.isActive == true) return
        val config = proximityConfigStore.config.value
        if (!shouldRunProximity(config)) return
        val leftRoom = leftRoomId?.let { id -> config.rooms.find { it.id == id } }
        if (leftRoom?.stopOnLeave != true) return
        if (!isWithinSchedule()) return
        val player = playerRepository.selectedPlayer.value ?: return
        if (player.state != PlaybackState.PLAYING) return
        noRoomStopArmedPlayerId = player.playerId

        noRoomStopJob = scope.launch {
            Log.d(TAG, "No-room stop timer armed ($reason): ${player.displayName} in 10m (left ${leftRoom.name})")
            delay(NO_ROOM_STOP_TIMEOUT_MS)

            val latestConfig = proximityConfigStore.config.value
            val currentRoom = roomDetector.currentRoom.value
            val armedPlayerId = noRoomStopArmedPlayerId
            val armedPlayer = armedPlayerId?.let { id ->
                playerRepository.players.value.find { it.playerId == id }
            }
            if (!shouldRunProximity(latestConfig)) return@launch
            if (currentRoom != null) return@launch
            if (armedPlayer == null || armedPlayer.state != PlaybackState.PLAYING) return@launch

            try {
                playerRepository.pause(armedPlayer.playerId)
                Log.d(TAG, "No-room stop timer fired: paused ${armedPlayer.displayName}")
            } catch (e: Exception) {
                Log.w(TAG, "No-room stop failed: ${e.message}")
            }
        }
    }
}
