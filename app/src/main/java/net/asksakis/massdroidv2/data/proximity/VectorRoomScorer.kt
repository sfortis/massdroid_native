package net.asksakis.massdroidv2.data.proximity

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

internal data class RoomFit(
    val roomId: String,
    val score: Double,
    val matchedAnchors: Int,
    val expectedAnchors: Int,
    val avgDelta: Double,
    val matchedPrimaryLocalAnchors: Int,
    val expectedPrimaryLocalAnchors: Int
)

/**
 * Vector-based room scoring using k-NN on calibration fingerprints.
 *
 * Instead of scoring each beacon independently, treats the entire scan as a
 * fingerprint vector and computes Euclidean distance to each calibration sample.
 * The room with the closest fingerprints wins.
 *
 * Key advantages over per-beacon Gaussian:
 *   - Shared anchors don't inflate wrong-room scores (the full pattern matters)
 *   - Missing anchors are penalized proportionally, not in isolation
 *   - Naturally handles rooms with different anchor counts
 */

private const val MISSING_RSSI = -100           // penalty RSSI for anchors not seen
private const val DEFAULT_DISTANCE_SCALE = 40.0  // normalize distance to ~0..1 range
private const val SOFTMAX_TEMPERATURE = 0.1
private const val K_NEAREST = 3                 // use 3 closest fingerprints per room

internal object VectorRoomScorer {

    /**
     * Score a room by finding the k nearest calibration fingerprints to the current scan.
     * Returns a RoomFit compatible with existing decision logic.
     */
    fun score(
        roomId: String,
        currentScan: Map<String, Int>,
        fingerprints: List<RoomFingerprint>,
        profiles: List<BeaconProfile>,
        primaryAnchors: Set<String>,
        distanceScale: Double = DEFAULT_DISTANCE_SCALE
    ): RoomFit {
        if (fingerprints.isEmpty()) {
            return RoomFit(roomId, Double.NEGATIVE_INFINITY, 0, profiles.size, Double.POSITIVE_INFINITY, 0, 0)
        }

        // All anchor keys across room's fingerprints (the room's "vocabulary")
        val roomAnchors = fingerprints.flatMap { it.samples.keys }.toSet()

        // Compute distance to each calibration fingerprint
        val distances = fingerprints.map { fp -> fingerprintDistance(currentScan, fp.samples, roomAnchors) }

        // k-NN: average of k closest distances
        val kNearest = distances.sorted().take(K_NEAREST.coerceAtMost(distances.size))
        val avgDistance = kNearest.average()

        // Convert distance to score: closer = higher score
        val score = exp(-avgDistance / distanceScale)

        // Compute diagnostic fields for logging compatibility
        val matched = primaryAnchors.count { it in currentScan }
        var deltaSum = 0.0
        var deltaCount = 0
        for (profile in profiles) {
            val observed = currentScan[profile.anchorKey] ?: continue
            deltaSum += abs(observed - profile.meanRssi).toDouble()
            deltaCount++
        }
        val avgDelta = if (deltaCount > 0) deltaSum / deltaCount else Double.POSITIVE_INFINITY
        val matchedPrimaryLocal = profiles.count { isDetectorPrimaryLocalAnchor(it) && it.anchorKey in currentScan }
        val expectedPrimaryLocal = profiles.count { isDetectorPrimaryLocalAnchor(it) }

        return RoomFit(
            roomId = roomId,
            score = score,
            matchedAnchors = matched,
            expectedAnchors = primaryAnchors.size,
            avgDelta = avgDelta,
            matchedPrimaryLocalAnchors = matchedPrimaryLocal,
            expectedPrimaryLocalAnchors = expectedPrimaryLocal
        )
    }

    /**
     * Weighted Euclidean distance between current scan and a calibration fingerprint.
     * Stronger signals (closer devices) contribute more to the distance, making
     * nearby anchors like a JBL speaker at -55dBm much more decisive than a
     * distant one at -90dBm.
     */
    private fun fingerprintDistance(
        current: Map<String, Int>,
        calibration: Map<String, Int>,
        roomAnchors: Set<String>
    ): Double {
        var weightedSumSquared = 0.0
        var totalWeight = 0.0
        for (anchor in roomAnchors) {
            val observed = current[anchor] ?: MISSING_RSSI
            val expected = calibration[anchor] ?: MISSING_RSSI
            // Weight: stronger calibrated signal = more important anchor
            val weight = signalWeight(expected)
            val diff = (observed - expected).toDouble()
            weightedSumSquared += weight * diff * diff
            totalWeight += weight
        }
        return if (totalWeight > 0) sqrt(weightedSumSquared / totalWeight) else Double.MAX_VALUE
    }

    /** Map RSSI to weight: -30dBm -> 3.0, -60dBm -> 2.0, -90dBm -> 1.0, -100dBm -> 0.7 */
    private fun signalWeight(rssi: Int): Double =
        ((rssi - MISSING_RSSI).toDouble() / 30.0).coerceIn(0.5, 3.0)

    fun topRoomProbability(rankedRooms: List<RoomFit>): Double {
        if (rankedRooms.isEmpty()) return 0.0
        val maxScore = rankedRooms.maxOf { it.score }
        val weights = rankedRooms.map { exp((it.score - maxScore) / SOFTMAX_TEMPERATURE) }
        val total = weights.sum().coerceAtLeast(1e-9)
        return (weights.first() / total).coerceIn(0.0, 1.0)
    }
}
