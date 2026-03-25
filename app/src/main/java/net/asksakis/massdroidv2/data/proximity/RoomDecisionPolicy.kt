package net.asksakis.massdroidv2.data.proximity

private const val STRONG_FIT_MIN_EXPECTED_ANCHORS = 6
private const val STRONG_FIT_MIN_MATCH_RATIO = 0.85
private const val STRONG_FIT_MAX_AVG_DELTA_DBM = 8.0

internal object RoomDecisionPolicy {
    fun evaluate(
        winnerRoom: RoomConfig,
        winner: RoomFit,
        runnerUpPresent: Boolean,
        winnerDetected: DetectedRoom,
        confidence: Double,
        margin: Double,
        startupDetection: Boolean,
        motionActive: Boolean,
        candidateWinCount: Int
    ): DetectResult {
        if (winner.expectedPrimaryLocalAnchors > 0 && winner.matchedPrimaryLocalAnchors == 0) {
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
            if (winner.matchedPrimaryLocalAnchors < winner.minimumPrimaryLocalMatchesForStartupConfirm()) {
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

        if (!strongFit && confidence < rules.minConfidence) {
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "low-confidence"
            )
        }
        if (!strongFit && runnerUpPresent && margin < rules.minMargin) {
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
