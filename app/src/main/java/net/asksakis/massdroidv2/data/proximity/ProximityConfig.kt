package net.asksakis.massdroidv2.data.proximity

import kotlinx.serialization.Serializable

@Serializable
enum class CalibrationQuality { UNCALIBRATED, WEAK, GOOD }

@Serializable
enum class DetectionPolicy { STRICT, RELAXED }

@Serializable
data class ProximityConfig(
    val enabled: Boolean = false,
    val autoTransfer: Boolean = false,
    val schedule: ProximitySchedule = ProximitySchedule(),
    val rooms: List<RoomConfig> = emptyList()
)

@Serializable
data class ProximitySchedule(
    val enabled: Boolean = false,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val startHour: Int = 7,
    val endHour: Int = 23
)

@Serializable
data class RoomConfig(
    val id: String,
    val name: String,
    val playerId: String,
    val playerName: String,
    val fingerprints: List<RoomFingerprint> = emptyList(),
    val beaconProfiles: List<BeaconProfile> = emptyList(),
    val calibrationQuality: CalibrationQuality = CalibrationQuality.UNCALIBRATED,
    val detectionPolicy: DetectionPolicy = DetectionPolicy.STRICT,
    val playbackConfig: RoomPlaybackConfig = RoomPlaybackConfig()
)

@Serializable
data class RoomPlaybackConfig(
    val playlistUri: String? = null,
    val playlistName: String? = null,
    val shuffle: Boolean = true,
    val volumeEnabled: Boolean = false,
    val volumeLevel: Int = 5
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
