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

/**
 * What Follow Me does on a confirmed room change:
 * - ASK: show a notification with a Move/Play action (let the user decide).
 * - AUTO_TRANSFER: move the current playback to the room player automatically.
 * - SELECT_ONLY: just select the room player so the user has controls ready (volume rocker, mini
 *   player), without transferring or prompting.
 */
@Serializable
enum class ProximityTransferMode { ASK, AUTO_TRANSFER, SELECT_ONLY }

data class PolicyRules(
    val minBleCoverage: Int,
    val minConfidence: Double,
    val minMargin: Double,
    val minConsecutiveWins: Int,
    val minUsableProfiles: Int
)

fun DetectionPolicy.rules(): PolicyRules = when (this) {
    DetectionPolicy.STRICT -> PolicyRules(
        minBleCoverage = 2,
        minConfidence = 0.6,
        minMargin = 4.0,
        minConsecutiveWins = 2,
        minUsableProfiles = 3
    )
    DetectionPolicy.NORMAL -> PolicyRules(
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
    // Legacy boolean kept only to migrate existing configs into [transferMode] (see
    // effectiveTransferMode). Do not read directly outside the resolver.
    val autoTransfer: Boolean = false,
    val transferMode: ProximityTransferMode? = null,
    val schedule: ProximitySchedule = ProximitySchedule(),
    val rooms: List<RoomConfig> = emptyList()
)

fun ProximityConfig.normalized(): ProximityConfig = copy(schedule = schedule.normalized())

/** Resolved transfer mode, migrating the legacy [autoTransfer] flag when [transferMode] is unset. */
fun ProximityConfig.effectiveTransferMode(): ProximityTransferMode =
    transferMode ?: if (autoTransfer) ProximityTransferMode.AUTO_TRANSFER else ProximityTransferMode.ASK

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
    val stopOnLeave: Boolean = false,
    /**
     * Per-room detection sensitivity in SENSITIVITY_MIN..SENSITIVITY_MAX. Higher = easier/faster to
     * confirm (lower confidence/margin gates). null = derive from the detection policy so existing
     * configs migrate without resetting. See confirmThresholds().
     */
    val sensitivity: Float? = null
)

/** Resolved confirm gates for the relative decision (see RoomDecisionPolicy). */
data class ConfirmThresholds(val minConfidence: Double, val minMargin: Double)

const val SENSITIVITY_MIN = 0f
const val SENSITIVITY_MAX = 100f

/** Sensitivity defaults per policy when a room hasn't been tuned (null). STRICT = stricter (lower). */
fun DetectionPolicy.defaultSensitivity(): Float = when (this) {
    DetectionPolicy.STRICT -> 35f
    DetectionPolicy.NORMAL -> 65f
}

/** Effective sensitivity, falling back to the policy default. */
fun RoomConfig.effectiveSensitivity(): Float =
    (sensitivity ?: detectionPolicy.defaultSensitivity()).coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)

/**
 * Map sensitivity to the confirm gates. Higher sensitivity lowers both gates (monotonic: easier to
 * confirm), matching the UI label. Ranges tuned offline against captured fingerprints so that
 * well-separated rooms confirm and confusable ones stay borderline at mid sensitivity.
 */
fun RoomConfig.confirmThresholds(): ConfirmThresholds {
    val s = (effectiveSensitivity() / SENSITIVITY_MAX).toDouble()  // 0..1
    var minConfidence = 0.65 - s * 0.20   // 0.65 (strict) -> 0.45 (relaxed)
    var minMargin = 2.0 - s * 1.6         // 2.0  (strict) -> 0.4  (relaxed)
    // Weakly-separable rooms (low self-recognition / a dominant confuser, see RoomSeparability) are
    // still eligible but must clear a stiffer bar so an overlapping neighbour can't flap them. GOOD
    // rooms keep the sensitivity-derived gates unchanged.
    if (calibrationQuality != CalibrationQuality.GOOD) {
        minConfidence += WEAK_ROOM_CONFIDENCE_PENALTY
        minMargin += WEAK_ROOM_MARGIN_PENALTY
    }
    return ConfirmThresholds(minConfidence = minConfidence, minMargin = minMargin)
}

private const val WEAK_ROOM_CONFIDENCE_PENALTY = 0.05
private const val WEAK_ROOM_MARGIN_PENALTY = 0.5

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
