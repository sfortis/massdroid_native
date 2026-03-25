package net.asksakis.massdroidv2.data.proximity

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "RoomDetector"
private const val KNN_K = 5
private const val COVERAGE_ANCHORS = 8
private const val STAY_BIAS_FACTOR = 0.85
private const val MISSING_PENALTY = 12.0
private const val TAIL_MISSING_PENALTY_FACTOR = 0.35
private const val RSSI_DIFF_CLAMP = 25
private const val NO_MATCH_CLEAR_THRESHOLD = 5

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

/**
 * Room classifier using fingerprint k-NN detection.
 * Computes weighted Manhattan distance to stored fingerprints,
 * votes by nearest neighbors, applies stay bias and confidence thresholds.
 */
@Singleton
class RoomDetector @Inject constructor() {

    private val _currentRoom = MutableStateFlow<DetectedRoom?>(null)
    val currentRoom: StateFlow<DetectedRoom?> = _currentRoom.asStateFlow()

    private var consecutiveWinnerId: String? = null
    private var consecutiveWinCount = 0
    private var noMatchStreak = 0
    @Volatile private var suppressed = false

    fun suppress() { suppressed = true }
    fun resume() { suppressed = false }

    /** Set current room directly (e.g., after calibration) without going through confidence logic */
    fun seedRoom(room: DetectedRoom) {
        _currentRoom.value = room
        consecutiveWinnerId = room.roomId
        consecutiveWinCount = 10
        Log.d(TAG, "Seeded room: ${room.roomName}")
    }

    fun detect(
        scanResults: Map<String, Int>,
        config: ProximityConfig,
        motionActive: Boolean = false,
        currentBssid: String? = null
    ): DetectedRoom? = when (val result = detectDetailed(scanResults, config, motionActive, currentBssid)) {
        is DetectResult.Confirmed -> result.room
        else -> null
    }

    fun detectDetailed(
        scanResults: Map<String, Int>,
        config: ProximityConfig,
        motionActive: Boolean = false,
        currentBssid: String? = null
    ): DetectResult {
        if (suppressed || config.rooms.isEmpty()) return DetectResult.NoDecision
        val wifiOnlyMatches = config.rooms.filter { room ->
            room.stickToConnectedWifi &&
                room.connectedBssid != null &&
                currentBssid != null &&
                room.connectedBssid == currentBssid
        }
        if (wifiOnlyMatches.isNotEmpty()) {
            if (wifiOnlyMatches.size > 1) {
                Log.w(TAG, "Multiple rooms matched connected Wi-Fi AP $currentBssid: ${wifiOnlyMatches.joinToString { it.name }}")
            }
            val winnerRoom = wifiOnlyMatches.find { it.id == _currentRoom.value?.roomId } ?: wifiOnlyMatches.first()
            val detected = DetectedRoom(winnerRoom.id, winnerRoom.name, winnerRoom.playerId, winnerRoom.playerName)
            noteWinnerCandidate(winnerRoom.id)
            val changed = _currentRoom.value?.roomId != winnerRoom.id
            _currentRoom.value = detected
            Log.d(TAG, "Wi-Fi AP override: ${winnerRoom.name} via $currentBssid")
            return if (changed) DetectResult.Confirmed(detected) else DetectResult.NoDecision
        }

        if (scanResults.isEmpty()) {
            handleNoMatch("empty scan")
            return DetectResult.NoCoverage
        }

        // Build eligible BLE rooms using centralized policy rules.
        val eligibleRooms = config.rooms.filter { room ->
            if (room.stickToConnectedWifi) return@filter false
            if (room.fingerprints.isEmpty()) return@filter false
            val rules = room.detectionPolicy.rules()
            if (!rules.allowWeakCalibration && room.calibrationQuality != CalibrationQuality.GOOD) return@filter false
            if (room.calibrationQuality == CalibrationQuality.UNCALIBRATED) return@filter false
            true
        }
        if (eligibleRooms.isEmpty()) return DetectResult.NoDecision

        // Rank BLE anchors once. Use the strongest subset for coverage/primary-anchor handling,
        // but score against the full calibrated fingerprint.
        val roomSortedBleProfiles = eligibleRooms.associate { room ->
            room.id to rankBeaconProfilesForDetection(
                room.beaconProfiles.filter { !it.address.startsWith("wifi:") }
            )
        }

        val roomPrimaryBleAnchors = roomSortedBleProfiles.mapValues { (_, bleProfiles) ->
            bleProfiles.take(COVERAGE_ANCHORS).map { it.address }.toSet()
        }

        // All calibrated beacons participate in scoring (the full pattern IS the fingerprint)
        val roomAllWeights = eligibleRooms.associate { room ->
            room.id to room.beaconProfiles
                .filter { !it.address.startsWith("wifi:") }
                .associate { it.address to it.weight }
        }

        data class FpEntry(val roomId: String, val distance: Double)

        val allDistances = mutableListOf<FpEntry>()
        for (room in eligibleRooms) {
            val rules = room.detectionPolicy.rules()
            val allWeights = roomAllWeights[room.id] ?: emptyMap()
            val primaryAnchors = roomPrimaryBleAnchors[room.id] ?: emptySet()
            val bleMatched = scanResults.keys.count { it in primaryAnchors }
            if (bleMatched < rules.minBleCoverage) continue

            for (fp in room.fingerprints) {
                val dist = fingerprintDistance(scanResults, fp, allWeights, primaryAnchors)
                val biased = if (_currentRoom.value?.roomId == room.id) dist * STAY_BIAS_FACTOR else dist
                // WiFi BSSID as supplementary scoring hint
                val bssidAdjusted = when {
                    room.connectedBssid == null || currentBssid == null -> biased
                    room.connectedBssid == currentBssid -> biased * 0.9
                    else -> biased * 1.1
                }
                allDistances.add(FpEntry(room.id, bssidAdjusted))
            }
        }

        if (allDistances.isEmpty()) {
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

        val topK = allDistances.sortedBy { it.distance }.take(KNN_K)
        val votes = topK.groupBy { it.roomId }.mapValues { it.value.size }
        val sortedVotes = votes.entries.sortedByDescending { it.value }
        val winnerId = sortedVotes.first().key
        val winnerVotes = sortedVotes.first().value
        val confidence = winnerVotes.toDouble() / topK.size

        val winnerAvgDist = topK.filter { it.roomId == winnerId }.map { it.distance }.average()
        val runnerUpAvgDist = topK.filter { it.roomId != winnerId }
            .map { it.distance }.takeIf { it.isNotEmpty() }?.average() ?: (winnerAvgDist + 5.0)
        val margin = runnerUpAvgDist - winnerAvgDist

        val winnerRoom = config.rooms.first { it.id == winnerId }
        val rules = winnerRoom.detectionPolicy.rules()
        val topRoomNames = topK.map { e -> config.rooms.first { it.id == e.roomId }.name }

        Log.d(TAG, "k-NN: winner=${winnerRoom.name}, conf=${String.format("%.2f", confidence)}, " +
            "margin=${String.format("%.1f", margin)}, devices=${scanResults.size}, " +
            "policy=${winnerRoom.detectionPolicy}, top$KNN_K=$topRoomNames")

        val winnerDetected = DetectedRoom(winnerRoom.id, winnerRoom.name, winnerRoom.playerId, winnerRoom.playerName)
        noteWinnerCandidate(winnerId)

        if (confidence < rules.minConfidence) {
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "low-confidence"
            )
        }
        if (sortedVotes.size > 1 && margin < rules.minMargin) {
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "low-margin"
            )
        }

        // During motion: fast confirm (1 win), otherwise use policy threshold
        val reqWins = if (motionActive) 1 else rules.minConsecutiveWins
        if (consecutiveWinCount < reqWins) {
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "needs-$reqWins-wins"
            )
        }

        val changed = _currentRoom.value?.roomId != winnerRoom.id
        _currentRoom.value = winnerDetected
        return if (changed) DetectResult.Confirmed(winnerDetected) else DetectResult.NoDecision
    }

    fun reset() {
        resetConfidence()
        noMatchStreak = 0
        _currentRoom.value = null
    }

    private fun handleNoMatch(reason: String) {
        noMatchStreak++
        if (noMatchStreak >= NO_MATCH_CLEAR_THRESHOLD && _currentRoom.value != null) {
            Log.d(TAG, "k-NN: left all rooms ($reason x$noMatchStreak)")
            resetConfidence()
            _currentRoom.value = null
        } else {
            Log.d(TAG, "k-NN: skip ($reason, streak=$noMatchStreak)")
        }
    }

    private fun fingerprintDistance(
        current: Map<String, Int>,
        fingerprint: RoomFingerprint,
        weights: Map<String, Double>,
        primaryAnchors: Set<String>
    ): Double {
        var total = 0.0
        var used = 0.0
        for ((addr, refRssi) in fingerprint.samples) {
            // Score the full fingerprint, but penalize missing tail anchors less aggressively.
            val weight = weights[addr] ?: continue
            val currentRssi = current[addr]
            val contribution = if (currentRssi != null) {
                weight * abs(currentRssi - refRssi).coerceAtMost(RSSI_DIFF_CLAMP)
            } else {
                val penaltyFactor = if (addr in primaryAnchors) 1.0 else TAIL_MISSING_PENALTY_FACTOR
                weight * MISSING_PENALTY * penaltyFactor
            }
            total += contribution
            used += weight
        }
        return if (used > 0.0) total / used else Double.MAX_VALUE
    }

    private fun resetConfidence() {
        consecutiveWinnerId = null
        consecutiveWinCount = 0
    }

    private fun noteWinnerCandidate(winnerId: String) {
        if (winnerId == consecutiveWinnerId) {
            consecutiveWinCount++
        } else {
            consecutiveWinnerId = winnerId
            consecutiveWinCount = 1
        }
    }
}
