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
private const val STAY_BIAS_FACTOR = 0.85
private const val MISSING_PENALTY = 12.0
private const val RSSI_DIFF_CLAMP = 25
private const val NO_MATCH_CLEAR_THRESHOLD = 5

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

    fun detect(scanResults: Map<String, Int>, config: ProximityConfig): DetectedRoom? {
        if (suppressed || config.rooms.isEmpty() || scanResults.isEmpty()) return null

        // Build eligible rooms using centralized policy rules
        val eligibleRooms = config.rooms.filter { room ->
            if (room.fingerprints.isEmpty()) return@filter false
            val rules = room.detectionPolicy.rules()
            if (!rules.allowWeakCalibration && room.calibrationQuality != CalibrationQuality.GOOD) return@filter false
            if (room.calibrationQuality == CalibrationQuality.UNCALIBRATED) return@filter false
            true
        }
        if (eligibleRooms.isEmpty()) return null

        val roomWeights = eligibleRooms.associate { room ->
            room.id to room.beaconProfiles.associate { it.address to it.weight }
        }

        data class FpEntry(val roomId: String, val distance: Double)

        val allDistances = mutableListOf<FpEntry>()
        for (room in eligibleRooms) {
            val rules = room.detectionPolicy.rules()
            // Coverage gate: BLE primary, WiFi allowed only for RELAXED
            val bleAddresses = room.fingerprints.flatMap { it.samples.keys }.filter { !it.startsWith("wifi:") }.toSet()
            val bleMatched = scanResults.keys.count { it in bleAddresses }
            if (bleMatched < rules.minBleCoverage) {
                if (!rules.allowWifiOnly) continue
                // WiFi-only fallback: need at least 2 WiFi matches
                val wifiAddresses = room.fingerprints.flatMap { it.samples.keys }.filter { it.startsWith("wifi:") }.toSet()
                val wifiMatched = scanResults.keys.count { it in wifiAddresses }
                if (wifiMatched < 2) continue
            }

            val weights = roomWeights[room.id] ?: emptyMap()
            for (fp in room.fingerprints) {
                val dist = fingerprintDistance(scanResults, fp, weights)
                val biased = if (_currentRoom.value?.roomId == room.id) dist * STAY_BIAS_FACTOR else dist
                allDistances.add(FpEntry(room.id, biased))
            }
        }

        if (allDistances.isEmpty()) {
            noMatchStreak++
            if (noMatchStreak >= NO_MATCH_CLEAR_THRESHOLD && _currentRoom.value != null) {
                Log.d(TAG, "k-NN: left all rooms (no match x$noMatchStreak)")
                resetConfidence()
                _currentRoom.value = null
            } else {
                Log.d(TAG, "k-NN: skip (devices=${scanResults.size}, no room matched coverage)")
            }
            return null
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

        if (confidence < rules.minConfidence) {
            resetConfidence()
            return null
        }
        if (sortedVotes.size > 1 && margin < rules.minMargin) {
            resetConfidence()
            return null
        }

        if (winnerId == consecutiveWinnerId) {
            consecutiveWinCount++
        } else {
            consecutiveWinnerId = winnerId
            consecutiveWinCount = 1
        }

        if (consecutiveWinCount < rules.minConsecutiveWins) return null

        val detected = DetectedRoom(winnerRoom.id, winnerRoom.name, winnerRoom.playerId, winnerRoom.playerName)
        val changed = _currentRoom.value?.roomId != winnerRoom.id
        _currentRoom.value = detected
        return if (changed) detected else null
    }

    fun reset() {
        resetConfidence()
        noMatchStreak = 0
        _currentRoom.value = null
    }

    private fun fingerprintDistance(
        current: Map<String, Int>,
        fingerprint: RoomFingerprint,
        weights: Map<String, Double>
    ): Double {
        var total = 0.0
        var used = 0.0
        for ((addr, refRssi) in fingerprint.samples) {
            val weight = weights[addr] ?: 1.0
            val currentRssi = current[addr]
            val contribution = if (currentRssi != null) {
                weight * abs(currentRssi - refRssi).coerceAtMost(RSSI_DIFF_CLAMP)
            } else {
                weight * MISSING_PENALTY
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
}
