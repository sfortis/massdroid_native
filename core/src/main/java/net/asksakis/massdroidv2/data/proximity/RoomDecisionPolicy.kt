package net.asksakis.massdroidv2.data.proximity

private const val STRONG_FIT_MIN_EXPECTED_ANCHORS = 6
private const val STRONG_FIT_MIN_MATCH_RATIO = 0.85
private const val STRONG_FIT_MAX_AVG_DELTA_DBM = 8.0

internal object RoomDecisionPolicy {
    @Suppress("LongParameterList")
    fun evaluate(
        winnerRoom: RoomConfig,
        winner: RoomFit,
        runnerUpPresent: Boolean,
        winnerDetected: DetectedRoom,
        confidence: Double,
        margin: Double,
        startupDetection: Boolean,
        motionActive: Boolean,
        candidateWinCount: Int,
        thresholds: ConfirmThresholds
    ): DetectResult {
        val soleCandidate = isSoleCandidate(winner, runnerUpPresent)
        if (!soleCandidate && winner.expectedPrimaryLocalAnchors > 0 && winner.matchedPrimaryLocalAnchors == 0) {
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "no-signature-anchor"
            )
        }

        if (startupDetection) {
            if (winner.matchedAnchors < winner.minimumMatchedAnchorsForStartupConfirm()) {
                return DetectResult.Borderline(
                    winner = winnerDetected,
                    confidence = confidence,
                    margin = margin,
                    reason = "insufficient-anchor-evidence"
                )
            }
            if (!soleCandidate &&
                winner.matchedPrimaryLocalAnchors < winner.minimumPrimaryLocalMatchesForStartupConfirm()
            ) {
                return DetectResult.Borderline(
                    winner = winnerDetected,
                    confidence = confidence,
                    margin = margin,
                    reason = "insufficient-primary-local-anchors"
                )
            }
        }

        val rules = winnerRoom.detectionPolicy.rules()
        val strongFit = winner.matchesStrongFitCriteria()

        // Confidence/margin gates come from the per-room sensitivity (see RoomConfig.confirmThresholds);
        // the policy still provides the evidence/coverage sub-rules (minConsecutiveWins below, etc.).
        if (!strongFit && confidence < thresholds.minConfidence) {
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "low-confidence"
            )
        }
        if (!strongFit && runnerUpPresent && margin < thresholds.minMargin) {
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "low-margin"
            )
        }

        val requiredWins = if (motionActive) 1 else rules.minConsecutiveWins
        if (candidateWinCount < requiredWins) {
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "needs-$requiredWins-wins"
            )
        }

        return DetectResult.Confirmed(winnerDetected)
    }

    fun isStrongFit(fit: RoomFit): Boolean = fit.matchesStrongFitCriteria()

    /**
     * Whether the winner is the only room the scan can be, so its signature anchor is not
     * required.
     *
     * The signature-anchor gate stops a room from being confirmed from a neighbouring space
     * that hears its shared anchors. That neighbour is normally another configured room, and
     * then it shows up as a runner-up. With no runner-up, at least half the winner's anchors
     * matched (the startup minimum), and their levels within the strong-fit delta, the scan
     * looks like the room itself. A room far from home (an office with one configured room)
     * was otherwise never confirmed whenever its one signature anchor, a battery
     * thermometer, did not advertise: on 2026-11-11 the office won 195 fits in a row with
     * confidence 1.00 and 4 to 6 of 8 anchors, and every one was held back.
     */
    fun isSoleCandidate(fit: RoomFit, runnerUpPresent: Boolean): Boolean {
        if (runnerUpPresent) return false
        if (fit.matchedAnchors < fit.minimumMatchedAnchorsForStartupConfirm()) return false
        return fit.avgDelta.isFinite() && fit.avgDelta <= STRONG_FIT_MAX_AVG_DELTA_DBM
    }

    private fun RoomFit.matchesStrongFitCriteria(): Boolean {
        if (expectedAnchors < STRONG_FIT_MIN_EXPECTED_ANCHORS) return false
        if (!avgDelta.isFinite() || avgDelta > STRONG_FIT_MAX_AVG_DELTA_DBM) return false
        val matchRatio = matchedAnchors.toDouble() / expectedAnchors.toDouble()
        if (matchRatio < STRONG_FIT_MIN_MATCH_RATIO) return false
        if (expectedPrimaryLocalAnchors > 0 && matchedPrimaryLocalAnchors < expectedPrimaryLocalAnchors) return false
        return true
    }

    private fun RoomFit.minimumMatchedAnchorsForStartupConfirm(): Int = when {
        expectedAnchors >= 6 -> 4
        expectedAnchors >= 4 -> 3
        expectedAnchors >= 2 -> 2
        else -> 1
    }

    private fun RoomFit.minimumPrimaryLocalMatchesForStartupConfirm(): Int = when {
        expectedPrimaryLocalAnchors >= 3 -> 2
        expectedPrimaryLocalAnchors >= 1 -> 1
        else -> 0
    }
}
