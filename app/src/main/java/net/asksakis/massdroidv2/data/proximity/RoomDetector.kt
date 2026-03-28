package net.asksakis.massdroidv2.data.proximity

import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RoomDetector"
private const val TOP_SCORE_ROOMS = 5
private const val COVERAGE_ANCHORS = 8
private const val STAY_BIAS_FACTOR = 1.08
private const val NO_MATCH_CLEAR_THRESHOLD = 5
private const val SCORE_MARGIN_SCALE = 10.0

sealed interface DetectResult {
    data class Confirmed(val room: DetectedRoom) : DetectResult
    data class Borderline(
        val winner: DetectedRoom,
        val confidence: Double,
        val margin: Double,
        val reason: String
    ) : DetectResult
    data object NoCoverage : DetectResult
    data object NoDecision : DetectResult
}

/** Room classifier using weighted Gaussian likelihood scoring over anchor fingerprints. */
@Singleton
class RoomDetector @Inject constructor() {
    data class WifiMatchContext(
        val bssid: String? = null,
        val ssid: String? = null
    )

    private data class WifiOverrideMatch(
        val room: RoomConfig,
        val detected: DetectedRoom,
        val changed: Boolean
    )

    private val _currentRoom = MutableStateFlow<DetectedRoom?>(null)
    val currentRoom: StateFlow<DetectedRoom?> = _currentRoom.asStateFlow()

    private var consecutiveWinnerId: String? = null
    private var consecutiveWinCount = 0
    private var noMatchStreak = 0
    @Volatile private var suppressed = false

    fun suppress() { suppressed = true }
    fun resume() { suppressed = false }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "RoomDetector must run on the main thread" }
    }

    /** Set current room directly (e.g., after calibration) without going through confidence logic */
    fun seedRoom(room: DetectedRoom) {
        assertMainThread()
        _currentRoom.value = room
        consecutiveWinnerId = room.roomId
        consecutiveWinCount = 10
        Log.d(TAG, "Seeded room: ${room.roomName}")
    }

    fun detect(
        scanResults: Map<String, Int>,
        config: ProximityConfig,
        motionActive: Boolean = false,
        wifi: WifiMatchContext = WifiMatchContext()
    ): DetectedRoom? = when (val result = detectDetailed(scanResults, config, motionActive, wifi)) {
        is DetectResult.Confirmed -> result.room
        else -> null
    }

    fun detectDetailed(
        scanResults: Map<String, Int>,
        config: ProximityConfig,
        motionActive: Boolean = false,
        wifi: WifiMatchContext = WifiMatchContext(),
        commitRoomChange: Boolean = true
    ): DetectResult {
        assertMainThread()
        if (suppressed || config.rooms.isEmpty()) return DetectResult.NoDecision
        resolveWifiOverride(config.rooms, wifi)?.let { match ->
            consecutiveWinnerId = match.room.id
            consecutiveWinCount = maxOf(consecutiveWinCount, 1)
            if (commitRoomChange) {
                _currentRoom.value = match.detected
            }
            Log.d(TAG, "Wi-Fi AP override: ${match.room.name} via ${wifi.bssid ?: wifi.ssid ?: "unknown"}")
            return if (match.changed) DetectResult.Confirmed(match.detected) else DetectResult.NoDecision
        }

        if (scanResults.isEmpty()) {
            handleNoMatch("empty scan")
            return DetectResult.NoCoverage
        }

        val eligibleRooms = eligibleBleRooms(config.rooms)
        if (eligibleRooms.isEmpty()) return DetectResult.NoDecision

        val roomPrimaryBleAnchors = buildPrimaryAnchorSets(eligibleRooms)
        val roomFits = buildRoomFits(
            eligibleRooms = eligibleRooms,
            scanResults = scanResults,
            roomPrimaryBleAnchors = roomPrimaryBleAnchors,
            wifi = wifi
        )

        if (roomFits.isEmpty()) {
            val liveBle = scanResults.keys.filter { !it.startsWith("wifi:") }.sorted()
            val coverageDetails = eligibleRooms.joinToString(" | ") { room ->
                val topBle = roomPrimaryBleAnchors[room.id].orEmpty().sorted()
                val matched = liveBle.filter { it in topBle }
                "${room.name}: matched=${matched.size}/${topBle.size}, live=$matched, top=$topBle"
            }
            handleNoMatch("devices=${scanResults.size}, no coverage, liveBle=$liveBle, rooms=[$coverageDetails]")
            return DetectResult.NoCoverage
        }
        noMatchStreak = 0

        val rankedRooms = roomFits.sortedByDescending { it.score }
        val winner = rankedRooms.first()
        val runnerUp = rankedRooms.getOrNull(1)
        val winnerId = winner.roomId
        val winnerScore = winner.score
        val runnerUpScore = runnerUp?.score ?: (winnerScore - 0.5)
        val margin = (winnerScore - runnerUpScore) * SCORE_MARGIN_SCALE
        val confidence = RoomFitScorer.topRoomProbability(rankedRooms)

        val winnerRoom = config.rooms.first { it.id == winnerId }
        val topRoomFits = rankedRooms
            .take(TOP_SCORE_ROOMS)
            .joinToString { fit -> formatRoomFitSummary(config, fit) }

        Log.d(
            TAG,
            "Fit: winner=${winnerRoom.name}, conf=${String.format("%.2f", confidence)}, " +
                "margin=${String.format("%.1f", margin)}, matched=${winner.matchedAnchors}/${winner.expectedAnchors}, " +
                "avgDelta=${formatAvgDelta(winner.avgDelta)}, primaryLocal=${winner.matchedPrimaryLocalAnchors}/${winner.expectedPrimaryLocalAnchors}, devices=${scanResults.size}, " +
                "policy=${winnerRoom.detectionPolicy}, top$TOP_SCORE_ROOMS=$topRoomFits"
        )

        val winnerDetected = winnerRoom.toDetectedRoom()
        val candidateWinCount = if (winnerId == consecutiveWinnerId) consecutiveWinCount + 1 else 1
        val strongFit = RoomDecisionPolicy.isStrongFit(winner)

        if (strongFit) {
            Log.d(
                TAG,
                "Fit: strong-match fast confirm for ${winnerRoom.name} " +
                    "(${formatStrongFitSummary(winner)})"
            )
        }
        val decision = RoomDecisionPolicy.evaluate(
            winnerRoom = winnerRoom,
            winner = winner,
            runnerUpPresent = runnerUp != null,
            winnerDetected = winnerDetected,
            confidence = confidence,
            margin = margin,
            startupDetection = _currentRoom.value == null,
            motionActive = motionActive,
            candidateWinCount = candidateWinCount
        )
        if (decision !is DetectResult.Confirmed) {
            consecutiveWinnerId = winnerId
            consecutiveWinCount = candidateWinCount
            return decision
        }

        consecutiveWinnerId = winnerId
        consecutiveWinCount = candidateWinCount
        val changed = _currentRoom.value?.roomId != winnerRoom.id
        if (commitRoomChange) {
            _currentRoom.value = winnerDetected
        }
        return if (changed) DetectResult.Confirmed(winnerDetected) else DetectResult.NoDecision
    }

    fun reset() {
        assertMainThread()
        resetConfidence()
        noMatchStreak = 0
        _currentRoom.value = null
    }

    private fun handleNoMatch(reason: String) {
        noMatchStreak++
        if (noMatchStreak >= NO_MATCH_CLEAR_THRESHOLD && _currentRoom.value != null) {
            Log.d(TAG, "Fit: left all rooms ($reason x$noMatchStreak)")
            resetConfidence()
            _currentRoom.value = null
        } else {
            Log.d(TAG, "Fit: skip ($reason, streak=$noMatchStreak)")
        }
    }

    private fun resolveWifiOverride(
        rooms: List<RoomConfig>,
        wifi: WifiMatchContext
    ): WifiOverrideMatch? {
        val exactBssidMatches = rooms.filter { room -> room.matchesWifi(wifi, allowSsidFallback = false) }
        val wifiOnlyMatches = if (exactBssidMatches.isNotEmpty()) {
            exactBssidMatches
        } else {
            rooms.filter { room -> room.matchesWifi(wifi, allowSsidFallback = true) }
        }
        if (wifiOnlyMatches.isEmpty()) return null
        if (wifiOnlyMatches.size > 1) {
            val currentRoomMatch = wifiOnlyMatches.find { it.id == _currentRoom.value?.roomId }
            if (currentRoomMatch == null) {
                Log.w(
                    TAG,
                    "Multiple rooms matched connected Wi-Fi ${wifi.bssid ?: wifi.ssid}: ${wifiOnlyMatches.joinToString { it.name }}"
                )
                return null
            }
        }
        val winnerRoom = wifiOnlyMatches.find { it.id == _currentRoom.value?.roomId } ?: wifiOnlyMatches.first()
        return WifiOverrideMatch(
            room = winnerRoom,
            detected = winnerRoom.toDetectedRoom(),
            changed = _currentRoom.value?.roomId != winnerRoom.id
        )
    }

    private fun eligibleBleRooms(rooms: List<RoomConfig>): List<RoomConfig> =
        rooms.filter { room ->
            if (room.stickToConnectedWifi) return@filter false
            if (room.beaconProfiles.isEmpty()) return@filter false
            val rules = room.detectionPolicy.rules()
            if (!rules.allowWeakCalibration && room.calibrationQuality != CalibrationQuality.GOOD) return@filter false
            if (room.calibrationQuality == CalibrationQuality.UNCALIBRATED) return@filter false
            true
        }

    private fun buildPrimaryAnchorSets(rooms: List<RoomConfig>): Map<String, Set<String>> =
        rooms.associate { room ->
            val rankedProfiles = rankBeaconProfilesForDetection(
                room.beaconProfiles.filter { !it.anchorKey.startsWith("wifi:") }
            )
            room.id to rankedProfiles.take(COVERAGE_ANCHORS).map { it.anchorKey }.toSet()
        }

    private fun buildRoomFits(
        eligibleRooms: List<RoomConfig>,
        scanResults: Map<String, Int>,
        roomPrimaryBleAnchors: Map<String, Set<String>>,
        wifi: WifiMatchContext
    ): List<RoomFit> {
        val roomFits = mutableListOf<RoomFit>()
        for (room in eligibleRooms) {
            val primaryAnchors = roomPrimaryBleAnchors[room.id] ?: emptySet()
            val bleMatched = scanResults.keys.count { it in primaryAnchors }
            if (bleMatched < room.detectionPolicy.rules().minBleCoverage) continue

            val fit = RoomFitScorer.score(room.id, scanResults, room.beaconProfiles, primaryAnchors)
            roomFits.add(fit.copy(score = adjustedRoomScore(room, fit.score, wifi)))
        }
        return roomFits
    }

    private fun adjustedRoomScore(
        room: RoomConfig,
        rawScore: Double,
        wifi: WifiMatchContext
    ): Double {
        val stayAdjustedScore = if (_currentRoom.value?.roomId == room.id) rawScore * STAY_BIAS_FACTOR else rawScore
        return when {
            room.matchesWifi(wifi, allowSsidFallback = false) -> stayAdjustedScore + 0.05
            room.matchesWifi(wifi, allowSsidFallback = true) -> stayAdjustedScore + 0.03
            wifi.bssid == null && wifi.ssid == null -> stayAdjustedScore
            else -> stayAdjustedScore - 0.05
        }
    }

    private fun RoomConfig.matchesWifi(
        wifi: WifiMatchContext,
        allowSsidFallback: Boolean
    ): Boolean {
        if (!stickToConnectedWifi) return false
        if (!connectedBssid.isNullOrBlank() && !wifi.bssid.isNullOrBlank() && connectedBssid.equals(wifi.bssid, ignoreCase = true)) {
            return true
        }
        if (!allowSsidFallback) return false
        return !connectedSsid.isNullOrBlank() &&
            !wifi.ssid.isNullOrBlank() &&
            connectedSsid.equals(wifi.ssid, ignoreCase = true)
    }

    private fun RoomConfig.toDetectedRoom(): DetectedRoom =
        DetectedRoom(id, name, playerId, playerName)

    private fun formatRoomFitSummary(config: ProximityConfig, fit: RoomFit): String {
        val roomName = config.rooms.first { it.id == fit.roomId }.name
        return "$roomName(m=${fit.matchedAnchors}/${fit.expectedAnchors},d=${formatAvgDelta(fit.avgDelta)},s=${String.format("%.2f", fit.score)})"
    }

    private fun formatStrongFitSummary(fit: RoomFit): String =
        "matched=${fit.matchedAnchors}/${fit.expectedAnchors}, " +
            "primaryLocal=${fit.matchedPrimaryLocalAnchors}/${fit.expectedPrimaryLocalAnchors}, " +
            "avgDelta=${formatAvgDelta(fit.avgDelta)}"

    private fun formatAvgDelta(value: Double): String =
        if (value.isFinite()) String.format("%.1f", value) else "n/a"

    private fun resetConfidence() {
        consecutiveWinnerId = null
        consecutiveWinCount = 0
    }

}
