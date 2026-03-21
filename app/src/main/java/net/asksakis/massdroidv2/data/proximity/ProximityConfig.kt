package net.asksakis.massdroidv2.data.proximity

import kotlinx.serialization.Serializable

@Serializable
data class ProximityConfig(
    val enabled: Boolean = false,
    val autoTransfer: Boolean = false,
    val dwellTimeSec: Int = DEFAULT_DWELL_TIME,
    val scanDuringIdlePlayback: Boolean = true,
    val rooms: List<RoomConfig> = emptyList()
) {
    companion object {
        const val DEFAULT_DWELL_TIME = 3
    }
}

@Serializable
data class RoomConfig(
    val id: String,
    val name: String,
    val playerId: String,
    val playerName: String,
    val beacons: List<BeaconConfig> = emptyList()
)

@Serializable
data class BeaconConfig(
    val address: String,
    val name: String,
    val referenceRssi: Int
)

data class DetectedRoom(
    val roomId: String,
    val roomName: String,
    val playerId: String,
    val playerName: String
)
