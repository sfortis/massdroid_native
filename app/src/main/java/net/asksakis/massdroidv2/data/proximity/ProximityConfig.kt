package net.asksakis.massdroidv2.data.proximity

import kotlinx.serialization.Serializable

@Serializable
enum class CalibrationQuality { UNCALIBRATED, WEAK, GOOD }

@Serializable
enum class DetectionPolicy { STRICT, NORMAL }

@Serializable
enum class AnchorType { MAC, NAME }

@Serializable
enum class WifiMatchMode { BSSID, SSID }

data class PolicyRules(
    val allowWeakCalibration: Boolean,
    val minBleCoverage: Int,
    val minConfidence: Double,
    val minMargin: Double,
    val minConsecutiveWins: Int,
    val minUsableProfiles: Int
)

fun DetectionPolicy.rules(): PolicyRules = when (this) {
    DetectionPolicy.STRICT -> PolicyRules(
        allowWeakCalibration = false,
        minBleCoverage = 2,
        minConfidence = 0.6,
        minMargin = 4.0,
        minConsecutiveWins = 2,
        minUsableProfiles = 3
    )
    DetectionPolicy.NORMAL -> PolicyRules(
        allowWeakCalibration = true,
        minBleCoverage = 1,
        minConfidence = 0.4,
        minMargin = 2.0,
        minConsecutiveWins = 1,
        minUsableProfiles = 1
    )
}

@Serializable
data class ProximityConfig(
    val enabled: Boolean = false,
    val autoTransfer: Boolean = false,
    val detectionTolerance: Float = 40.0f,
    val schedule: ProximitySchedule = ProximitySchedule(),
    val rooms: List<RoomConfig> = emptyList()
)

fun ProximityConfig.normalized(): ProximityConfig = copy(schedule = schedule.normalized())

@Serializable
data class ProximitySchedule(
    val enabled: Boolean = false,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val startMinuteOfDay: Int? = null,
    val endMinuteOfDay: Int? = null,
    val startHour: Int? = DEFAULT_START_HOUR,
    val endHour: Int? = DEFAULT_END_HOUR
) {
    val effectiveStartMinuteOfDay: Int
        get() = (startMinuteOfDay ?: ((startHour ?: DEFAULT_START_HOUR) * 60)).coerceIn(0, MINUTES_PER_DAY - 1)

    val effectiveEndMinuteOfDay: Int
        get() = (endMinuteOfDay ?: ((endHour ?: DEFAULT_END_HOUR) * 60)).coerceIn(0, MINUTES_PER_DAY - 1)

    fun normalized(): ProximitySchedule = copy(
        startMinuteOfDay = effectiveStartMinuteOfDay,
        endMinuteOfDay = effectiveEndMinuteOfDay,
        startHour = null,
        endHour = null
    )
}

private const val MINUTES_PER_DAY = 24 * 60
private const val DEFAULT_START_HOUR = 7
private const val DEFAULT_END_HOUR = 23

fun formatMinuteOfDay(minuteOfDay: Int): String {
    val normalized = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val hour = normalized / 60
    val minute = normalized % 60
    return String.format("%02d:%02d", hour, minute)
}

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
    val playbackConfig: RoomPlaybackConfig = RoomPlaybackConfig(),
    val connectedBssid: String? = null,
    val connectedSsid: String? = null,
    val wifiMatchMode: WifiMatchMode? = null,
    val stopOnLeave: Boolean = false
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
    val weight: Double,
    val anchorKey: String = address,
    val anchorType: AnchorType = AnchorType.MAC
)

data class DetectedRoom(
    val roomId: String,
    val roomName: String,
    val playerId: String,
    val playerName: String
)
