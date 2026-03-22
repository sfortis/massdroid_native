package net.asksakis.massdroidv2.data.proximity

import kotlinx.serialization.Serializable

@Serializable
enum class CalibrationQuality { UNCALIBRATED, WEAK, GOOD }

@Serializable
data class ProximityConfig(
    val enabled: Boolean = false,
    val autoTransfer: Boolean = false,
    val rooms: List<RoomConfig> = emptyList()
)

@Serializable
data class RoomConfig(
    val id: String,
    val name: String,
    val playerId: String,
    val playerName: String,
    val fingerprints: List<RoomFingerprint> = emptyList(),
    val beaconProfiles: List<BeaconProfile> = emptyList(),
    val calibrationQuality: CalibrationQuality = CalibrationQuality.UNCALIBRATED
)

@Serializable
data class RoomFingerprint(
    val id: String,
    val label: String,
    val samples: Map<String, Int>,
    val capturedAtMs: Long
)

@Serializable
data class BeaconProfile(
    val address: String,
    val name: String,
    val meanRssi: Int,
    val variance: Double,
    val visibilityRate: Double,
    val discriminationScore: Double,
    val weight: Double
)

data class DetectedRoom(
    val roomId: String,
    val roomName: String,
    val playerId: String,
    val playerName: String
)
