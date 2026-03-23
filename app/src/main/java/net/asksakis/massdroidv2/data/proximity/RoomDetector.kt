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
private const val MIN_CONFIDENCE = 0.6
private const val MIN_MARGIN = 4.0
private const val MIN_CONSECUTIVE_WINS = 2
private const val STAY_BIAS_FACTOR = 0.85
private const val MISSING_PENALTY = 12.0
private const val RSSI_DIFF_CLAMP = 25
private const val MIN_MATCHED_BEACONS = 2
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
        consecutiveWinCount = MIN_CONSECUTIVE_WINS
        Log.d(TAG, "Seeded room: ${room.roomName}")
    }

    fun detect(scanResults: Map<String, Int>, config: ProximityConfig): DetectedRoom? {
        if (suppressed || config.rooms.isEmpty() || scanResults.isEmpty()) return null

        val roomWeights = config.rooms.associate { room ->
            room.id to room.beaconProfiles.associate { it.address to it.weight }
        }

        // Check minimum beacon coverage: scan must match at least MIN_MATCHED_BEACONS known addresses
        val allKnownAddresses = config.rooms
            .flatMap { r -> r.fingerprints.flatMap { it.samples.keys } }.toSet()
        val matchedCount = scanResults.keys.count { it in allKnownAddresses }
        if (matchedCount < MIN_MATCHED_BEACONS) {
            noMatchStreak++
            if (noMatchStreak >= NO_MATCH_CLEAR_THRESHOLD && _currentRoom.value != null) {
                Log.d(TAG, "k-NN: left all rooms (no match x$noMatchStreak)")
                resetConfidence()
                _currentRoom.value = null
            } else {
                Log.d(TAG, "k-NN: skip (devices=${scanResults.size}, matched=$matchedCount)")
            }
            return null
        }
        noMatchStreak = 0

        data class FpEntry(val roomId: String, val distance: Double)

        val allDistances = mutableListOf<FpEntry>()
        for (room in config.rooms) {
            if (room.fingerprints.isEmpty()) continue
            val allowWeak = room.detectionPolicy == DetectionPolicy.RELAXED
            if (!allowWeak && room.calibrationQuality != CalibrationQuality.GOOD) continue
            if (allowWeak && room.calibrationQuality == CalibrationQuality.UNCALIBRATED) continue
            val weights = roomWeights[room.id] ?: emptyMap()
            for (fp in room.fingerprints) {
                val dist = fingerprintDistance(scanResults, fp, weights)
                // Proportional stay bias: current room distances scaled down
                val biased = if (_currentRoom.value?.roomId == room.id) dist * STAY_BIAS_FACTOR else dist
                allDistances.add(FpEntry(room.id, biased))
            }
        }

        if (allDistances.isEmpty()) return null

        val topK = allDistances.sortedBy { it.distance }.take(KNN_K)
        val votes = topK.groupBy { it.roomId }.mapValues { it.value.size }
        val sortedVotes = votes.entries.sortedByDescending { it.value }
        val winnerId = sortedVotes.first().key
        val winnerVotes = sortedVotes.first().value
        val confidence = winnerVotes.toDouble() / topK.size

        val winnerAvgDist = topK.filter { it.roomId == winnerId }.map { it.distance }.average()
        val runnerUpAvgDist = topK.filter { it.roomId != winnerId }
            .map { it.distance }.takeIf { it.isNotEmpty() }?.average() ?: (winnerAvgDist + MIN_MARGIN + 1)
        val margin = runnerUpAvgDist - winnerAvgDist

        val winnerRoom = config.rooms.first { it.id == winnerId }
        val topRoomNames = topK.map { e -> config.rooms.first { it.id == e.roomId }.name }

        Log.d(TAG, "k-NN: winner=${winnerRoom.name}, conf=${String.format("%.2f", confidence)}, " +
            "margin=${String.format("%.1f", margin)}, devices=${scanResults.size}, " +
            "top$KNN_K=$topRoomNames")

        if (confidence < MIN_CONFIDENCE) {
            resetConfidence()
            return null
        }
        if (sortedVotes.size > 1 && margin < MIN_MARGIN) {
            resetConfidence()
            return null
        }

        if (winnerId == consecutiveWinnerId) {
            consecutiveWinCount++
        } else {
            consecutiveWinnerId = winnerId
            consecutiveWinCount = 1
        }

        if (consecutiveWinCount < MIN_CONSECUTIVE_WINS) return null

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
