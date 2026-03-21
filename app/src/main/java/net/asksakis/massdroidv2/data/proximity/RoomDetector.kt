package net.asksakis.massdroidv2.data.proximity

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "RoomDetector"
private const val MIN_VISIBLE_BEACONS = 2
private const val SIGNAL_WEIGHT = 1.0
private const val DEVIATION_PENALTY = 2.0
private const val MISSING_BEACON_PENALTY = 20.0
private const val EMA_ALPHA = 0.7
private const val MIN_CONFIDENCE_SCANS = 1
private const val MIN_MARGIN = 3.0

/**
 * Room classifier using anchored beacons only.
 * Requires same winner for MIN_CONFIDENCE_SCANS consecutive bursts
 * with MIN_MARGIN over second-best room before confirming room change.
 */
@Singleton
class RoomDetector @Inject constructor() {

    private val _currentRoom = MutableStateFlow<DetectedRoom?>(null)
    val currentRoom: StateFlow<DetectedRoom?> = _currentRoom.asStateFlow()

    private val rssiEma = mutableMapOf<String, Double>()
    private var consecutiveWinnerId: String? = null
    private var consecutiveWinCount = 0

    fun detect(scanResults: Map<String, Int>, config: ProximityConfig): DetectedRoom? {
        if (config.rooms.isEmpty() || scanResults.isEmpty()) return null

        // Use raw values directly (no EMA lag, persistent scan already smooths)
        val beaconAddresses = config.rooms.flatMap { r -> r.beacons.map { it.address } }.toSet()
        val smoothed = scanResults.filter { it.key in beaconAddresses }

        val scored = config.rooms.mapNotNull { room ->
            val score = scoreRoom(room, smoothed) ?: return@mapNotNull null
            room to score
        }.sortedByDescending { it.second }

        val winner = scored.firstOrNull() ?: run {
            resetConfidence()
            _currentRoom.value = null
            return null
        }

        val (room, winnerScore) = winner
        val secondScore = scored.getOrNull(1)?.second ?: Double.MIN_VALUE
        val margin = winnerScore - secondScore

        Log.d(TAG, "${room.name}: score=${String.format("%.1f", winnerScore)}, " +
            "margin=${String.format("%.1f", margin)}, " +
            "confidence=$consecutiveWinCount/${MIN_CONFIDENCE_SCANS}")

        // Margin check: winner must clearly beat second place
        if (scored.size > 1 && margin < MIN_MARGIN) {
            resetConfidence()
            return null
        }

        // Confidence: same winner for N consecutive scans
        if (room.id == consecutiveWinnerId) {
            consecutiveWinCount++
        } else {
            consecutiveWinnerId = room.id
            consecutiveWinCount = 1
        }

        if (consecutiveWinCount < MIN_CONFIDENCE_SCANS) return null

        val detected = DetectedRoom(room.id, room.name, room.playerId, room.playerName)
        val changed = _currentRoom.value?.roomId != room.id
        _currentRoom.value = detected
        return if (changed) detected else null
    }

    fun reset() {
        rssiEma.clear()
        resetConfidence()
        _currentRoom.value = null
    }

    private fun scoreRoom(room: RoomConfig, scanResults: Map<String, Int>): Double? {
        if (room.beacons.size < MIN_VISIBLE_BEACONS) return null
        if (room.beacons.count { it.address in scanResults } < MIN_VISIBLE_BEACONS) return null

        var score = 0.0
        for (beacon in room.beacons) {
            val currentRssi = scanResults[beacon.address]
            if (currentRssi != null) {
                val strength = (currentRssi + 100).coerceAtLeast(0).toDouble()
                val deviation = abs(currentRssi - beacon.referenceRssi).toDouble()
                score += strength * SIGNAL_WEIGHT - deviation * DEVIATION_PENALTY
            } else {
                score -= MISSING_BEACON_PENALTY
            }
        }
        return score / room.beacons.size
    }

    private fun resetConfidence() {
        consecutiveWinnerId = null
        consecutiveWinCount = 0
    }
}
