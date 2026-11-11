package net.asksakis.massdroidv2.data.proximity

private const val STRONG_FIT_MIN_EXPECTED_ANCHORS = 6
private const val STRONG_FIT_MIN_MATCH_RATIO = 0.85
private const val STRONG_FIT_MAX_AVG_DELTA_DBM = 8.0

/** What a scan heard of the BLE rooms other than the winner. */
internal enum class OtherRoomsHeard {
    /** No other room with BLE anchors is configured, so silence from other rooms says nothing. */
    NONE_CONFIGURED,

    /** At least one anchor of another room was heard, even below that room's coverage minimum. */
    HEARD,

    /** Other rooms are configured and the scan heard none of their anchors. */
    SILENT
}

internal object RoomDecisionPolicy {
    @Suppress("LongParameterList")
    fun evaluate(
        winnerRoom: RoomConfig,
        winner: RoomFit,
        runnerUpPresent: Boolean,
        otherRooms: OtherRoomsHeard,
        winnerDetected: DetectedRoom,
        confidence: Double,
        margin: Double,
        startupDetection: Boolean,
        motionActive: Boolean,
        candidateWinCount: Int,
        thresholds: ConfirmThresholds
    ): DetectResult {
        val soleCandidate = isSoleCandidate(winner, runnerUpPresent, otherRooms)
        if (!soleCandidate && winner.lacksSignatureAnchor()) {
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
            if (!soleCandidate && winner.lacksStartupPrimaryLocalAnchors()) {
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
     * that hears its shared anchors. The exception needs positive evidence that no other room
     * is near: at least one other BLE room is configured, and the scan heard none of its
     * anchors. A runner-up is not enough to test, because a room enters the fits only once its
     * coverage minimum is heard, so a neighbour heard on one anchor never shows up as one. With
     * a single BLE room nothing can show that the phone is not next to it, and the gate stays.
     * The winner also needs at least the startup minimum of its anchors, at levels within the
     * strong-fit delta.
     *
     * A room away from home was otherwise never confirmed whenever its one signature anchor,
     * a battery thermometer, did not advertise: on 2026-11-11 the office won 195 fits in a row
     * with confidence 1.00 and 4 to 6 of 8 anchors while every home room matched 0 of 8, and
     * every fit was held back.
     */
    fun isSoleCandidate(fit: RoomFit, runnerUpPresent: Boolean, otherRooms: OtherRoomsHeard): Boolean {
        if (runnerUpPresent || otherRooms != OtherRoomsHeard.SILENT) return false
        if (fit.matchedAnchors < fit.minimumMatchedAnchorsForStartupConfirm()) return false
        return fit.avgDelta.isFinite() && fit.avgDelta <= STRONG_FIT_MAX_AVG_DELTA_DBM
    }

    /**
     * Whether the sole-candidate exception is what lets this fit past a signature-anchor
     * check, as opposed to the fit passing those checks on its own.
     */
    fun soleCandidateWaivesSignatureGate(
        fit: RoomFit,
        runnerUpPresent: Boolean,
        otherRooms: OtherRoomsHeard,
        startupDetection: Boolean
    ): Boolean {
        val gated = fit.lacksSignatureAnchor() || (startupDetection && fit.lacksStartupPrimaryLocalAnchors())
        return gated && isSoleCandidate(fit, runnerUpPresent, otherRooms)
    }

    /**
     * What the scan heard of the rooms other than [winnerId]. [roomAnchors] holds each
     * eligible BLE room's detection anchors; a single anchor of another room counts as heard.
     */
    fun otherRoomsHeard(
        winnerId: String,
        heardAnchors: Set<String>,
        roomAnchors: Map<String, Set<String>>
    ): OtherRoomsHeard {
        val others = roomAnchors.filter { (roomId, anchors) -> roomId != winnerId && anchors.isNotEmpty() }
        return when {
            others.isEmpty() -> OtherRoomsHeard.NONE_CONFIGURED
            others.values.any { anchors -> anchors.any { it in heardAnchors } } -> OtherRoomsHeard.HEARD
            else -> OtherRoomsHeard.SILENT
        }
    }

    private fun RoomFit.lacksSignatureAnchor(): Boolean =
        expectedPrimaryLocalAnchors > 0 && matchedPrimaryLocalAnchors == 0

    private fun RoomFit.lacksStartupPrimaryLocalAnchors(): Boolean =
        matchedPrimaryLocalAnchors < minimumPrimaryLocalMatchesForStartupConfirm()

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
