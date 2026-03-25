package net.asksakis.massdroidv2.data.proximity

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

private const val MIN_SIGMA_DBM = 4.0
private const val MAX_SIGMA_DBM = 18.0
private const val PRIMARY_MISSING_PENALTY = 1.0
private const val TAIL_MISSING_PENALTY_FACTOR = 0.35
private const val SOFTMAX_TEMPERATURE = 0.3
private const val MATCH_QUALITY_SCORE_WEIGHT = 0.55
private const val COVERAGE_SCORE_WEIGHT = 0.25
private const val PRIMARY_LOCAL_SCORE_WEIGHT = 0.20
private const val DELTA_NORMALIZATION_DBM = 20.0
private const val AVG_DELTA_PENALTY_WEIGHT = 0.75

internal data class RoomFit(
    val roomId: String,
    val score: Double,
    val matchedAnchors: Int,
    val expectedAnchors: Int,
    val avgDelta: Double,
    val matchedPrimaryLocalAnchors: Int,
    val expectedPrimaryLocalAnchors: Int
)

internal object RoomFitScorer {
    fun score(
        roomId: String,
        current: Map<String, Int>,
        profiles: List<BeaconProfile>,
        primaryAnchors: Set<String>
    ): RoomFit {
        var total = 0.0
        var totalWeight = 0.0
        var matchedFitTotal = 0.0
        var matchedWeightTotal = 0.0
        var matched = 0
        var deltaSum = 0.0
        var matchedPrimaryLocalAnchors = 0
        val expectedPrimaryLocalAnchors = profiles.count { isDetectorPrimaryLocalAnchor(it) }

        for (profile in profiles) {
            val currentRssi = current[profile.anchorKey]
            val weight = detectorEffectiveWeight(profile)
            totalWeight += weight
            if (currentRssi != null) {
                val fit = gaussianSimilarity(
                    rssi = currentRssi.toDouble(),
                    mean = profile.meanRssi.toDouble(),
                    sigma = profileSigma(profile.variance)
                )
                total += weight * fit
                matchedFitTotal += weight * fit
                matchedWeightTotal += weight
                matched++
                deltaSum += abs(currentRssi - profile.meanRssi).toDouble()
                if (isDetectorPrimaryLocalAnchor(profile)) matchedPrimaryLocalAnchors++
            } else {
                val penaltyFactor = if (profile.anchorKey in primaryAnchors) 1.0 else TAIL_MISSING_PENALTY_FACTOR
                total -= weight * profile.visibilityRate.coerceAtLeast(0.25) * PRIMARY_MISSING_PENALTY * penaltyFactor
            }
        }

        val avgDelta = if (matched > 0) deltaSum / matched else Double.POSITIVE_INFINITY
        val normalizedScore = if (totalWeight > 0.0) total / totalWeight else Double.NEGATIVE_INFINITY
        val matchedQualityScore = if (matchedWeightTotal > 0.0) matchedFitTotal / matchedWeightTotal else 0.0
        val coverageScore = if (profiles.isNotEmpty()) matched.toDouble() / profiles.size.toDouble() else 0.0
        val primaryLocalCoverageScore = if (expectedPrimaryLocalAnchors > 0) {
            matchedPrimaryLocalAnchors.toDouble() / expectedPrimaryLocalAnchors.toDouble()
        } else {
            0.0
        }
        val avgDeltaPenalty = if (avgDelta.isFinite()) {
            (avgDelta / DELTA_NORMALIZATION_DBM) * AVG_DELTA_PENALTY_WEIGHT
        } else {
            AVG_DELTA_PENALTY_WEIGHT
        }
        val score =
            (matchedQualityScore * MATCH_QUALITY_SCORE_WEIGHT) +
                (coverageScore * COVERAGE_SCORE_WEIGHT) +
                (primaryLocalCoverageScore * PRIMARY_LOCAL_SCORE_WEIGHT) -
                avgDeltaPenalty +
                (normalizedScore * 0.10)

        return RoomFit(
            roomId = roomId,
            score = score,
            matchedAnchors = matched,
            expectedAnchors = profiles.size,
            avgDelta = avgDelta,
            matchedPrimaryLocalAnchors = matchedPrimaryLocalAnchors,
            expectedPrimaryLocalAnchors = expectedPrimaryLocalAnchors
        )
    }

    fun topRoomProbability(rankedRooms: List<RoomFit>): Double {
        if (rankedRooms.isEmpty()) return 0.0
        val maxScore = rankedRooms.maxOf { it.score }
        val weights = rankedRooms.map { exp((it.score - maxScore) / SOFTMAX_TEMPERATURE) }
        val total = weights.sum().coerceAtLeast(1e-9)
        return (weights.first() / total).coerceIn(0.0, 1.0)
    }

    private fun profileSigma(variance: Double): Double =
        sqrt(variance).coerceIn(MIN_SIGMA_DBM, MAX_SIGMA_DBM)

    private fun gaussianSimilarity(rssi: Double, mean: Double, sigma: Double): Double {
        val z = (rssi - mean) / sigma
        return exp(-0.5 * z * z).coerceIn(0.0, 1.0)
    }
}
