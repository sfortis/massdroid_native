package net.asksakis.massdroidv2.data.proximity

/**
 * Per-room result of the joint separability analysis.
 *
 * @param selfRecognition fraction of the room's own fingerprints that classify back to itself
 *        (leave-one-out) when scored against the whole room set.
 * @param topConfuserId the other room this room is most often mistaken for (null if none).
 * @param topConfuserRate fraction of this room's fingerprints misclassified as [topConfuserId].
 */
data class RoomConfusion(
    val roomId: String,
    val fingerprintCount: Int,
    val selfRecognition: Double,
    val topConfuserId: String?,
    val topConfuserRate: Double,
    val quality: CalibrationQuality
)

/**
 * Joint room-separability analysis (replaces the per-anchor, order-dependent discrimination gate).
 *
 * Instead of asking "does any single beacon of room R separate it from the others by >= N dBm?"
 * (which misses pattern-level separability and silently goes stale when neighbouring rooms are added
 * or re-calibrated), this cross-classifies every room's calibration fingerprints against the WHOLE
 * room set with [VectorRoomScorer] - the same classifier used at detection time. A leave-one-out
 * scheme excludes each fingerprint from its own room while scoring so the self-match isn't trivially
 * zero-distance.
 *
 * Quality is then derived from the confusion row:
 *   - high self-recognition AND no dominant confuser -> GOOD
 *   - otherwise (recognisable but confusable, or weakly recognisable) -> WEAK
 *
 * Because the analysis is recomputed over the full set whenever any room is (re)calibrated, it is
 * order-independent: a room can no longer flip GOOD -> WEAK just because a neighbour was calibrated
 * later. A genuinely confusable pair is honestly reported (see [RoomConfusion.topConfuserId]).
 */
object RoomSeparability {
    private const val COVERAGE_ANCHORS = 8
    private const val GOOD_SELF_RECOGNITION = 0.70
    private const val MAX_CONFUSER_RATE = 0.30

    /**
     * @return confusion result keyed by room id, only for rooms that carry usable calibration data
     *         (fingerprints + beacon profiles). Rooms without fingerprints are omitted (their quality
     *         should stay UNCALIBRATED).
     */
    fun analyze(rooms: List<RoomConfig>): Map<String, RoomConfusion> {
        val scorable = rooms.filter { it.fingerprints.isNotEmpty() && it.beaconProfiles.isNotEmpty() }
        if (scorable.isEmpty()) return emptyMap()

        val primaryAnchors = scorable.associate { room ->
            room.id to rankBeaconProfilesForDetection(
                room.beaconProfiles.filter { !it.anchorKey.startsWith("wifi:") }
            ).take(COVERAGE_ANCHORS).map { it.anchorKey }.toSet()
        }

        return scorable.associate { room ->
            val confusionCounts = mutableMapOf<String, Int>()
            var selfHits = 0
            var total = 0
            for (fp in room.fingerprints) {
                val predicted = scorable.maxByOrNull { target ->
                    val targetFingerprints =
                        if (target.id == room.id) target.fingerprints.filter { it.id != fp.id }
                        else target.fingerprints
                    if (targetFingerprints.isEmpty()) {
                        Double.NEGATIVE_INFINITY
                    } else {
                        VectorRoomScorer.score(
                            roomId = target.id,
                            currentScan = fp.samples,
                            fingerprints = targetFingerprints,
                            profiles = target.beaconProfiles,
                            primaryAnchors = primaryAnchors[target.id] ?: emptySet()
                        ).score
                    }
                }?.id ?: continue
                total++
                if (predicted == room.id) selfHits++ else {
                    confusionCounts[predicted] = (confusionCounts[predicted] ?: 0) + 1
                }
            }

            val selfRecognition = if (total > 0) selfHits.toDouble() / total else 0.0
            val topConfuser = confusionCounts.maxByOrNull { it.value }
            val topConfuserRate = if (total > 0 && topConfuser != null) {
                topConfuser.value.toDouble() / total
            } else {
                0.0
            }
            val quality = when {
                total == 0 -> CalibrationQuality.UNCALIBRATED
                selfRecognition >= GOOD_SELF_RECOGNITION && topConfuserRate <= MAX_CONFUSER_RATE ->
                    CalibrationQuality.GOOD
                // Recognisable-but-confusable, or weakly recognisable: still usable, stiffer confirm
                // bar (see RoomConfig.confirmThresholds). Never auto-demote to UNCALIBRATED here - that
                // is reserved for rooms that were never calibrated.
                else -> CalibrationQuality.WEAK
            }

            room.id to RoomConfusion(
                roomId = room.id,
                fingerprintCount = total,
                selfRecognition = selfRecognition,
                topConfuserId = topConfuser?.key,
                topConfuserRate = topConfuserRate,
                quality = quality
            )
        }
    }
}
