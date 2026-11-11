package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The signature-anchor gate and its one exception: a room that is the only candidate in
 * the scan. The fits replay the office arrival of 2026-11-11, where the room's signature
 * anchor (a battery thermometer) never advertised and every fit was held back.
 */
class RoomDecisionPolicyTest {

    private val office = RoomConfig(
        id = "office",
        name = "Office",
        playerId = "p-office",
        playerName = "Office speaker",
        detectionPolicy = DetectionPolicy.STRICT
    )

    private val thresholds = ConfirmThresholds(minConfidence = 0.6, minMargin = 1.0)

    private fun fit(matched: Int, avgDelta: Double, primaryMatched: Int = 0) = RoomFit(
        roomId = "office",
        score = 0.43,
        matchedAnchors = matched,
        expectedAnchors = 8,
        avgDelta = avgDelta,
        matchedPrimaryLocalAnchors = primaryMatched,
        expectedPrimaryLocalAnchors = 1
    )

    private fun evaluate(fit: RoomFit, runnerUpPresent: Boolean = false): DetectResult =
        RoomDecisionPolicy.evaluate(
            winnerRoom = office,
            winner = fit,
            runnerUpPresent = runnerUpPresent,
            winnerDetected = DetectedRoom("office", "Office", "p-office", "Office speaker"),
            confidence = 1.0,
            margin = 5.0,
            startupDetection = true,
            motionActive = true,
            candidateWinCount = 1,
            thresholds = thresholds
        )

    private fun reason(result: DetectResult): String? = (result as? DetectResult.Borderline)?.reason

    /** 12:34:25: 4 of 8 anchors at 6.2 dBm, no other room heard, signature anchor silent. */
    @Test
    fun `the only candidate is confirmed without its signature anchor`() {
        assertThat(evaluate(fit(matched = 4, avgDelta = 6.2))).isInstanceOf(DetectResult.Confirmed::class.java)
    }

    /** A neighbouring room in the scan brings the gate back. */
    @Test
    fun `a runner-up keeps the signature anchor required`() {
        val result = evaluate(fit(matched = 4, avgDelta = 6.2), runnerUpPresent = true)
        assertThat(reason(result)).isEqualTo("no-signature-anchor")
    }

    /** 12:34:20: 2 of 8 anchors, still walking in. Too little evidence. */
    @Test
    fun `fewer than half the anchors keep the gate`() {
        assertThat(reason(evaluate(fit(matched = 2, avgDelta = 6.0)))).isEqualTo("no-signature-anchor")
    }

    /** 12:34:24: 4 of 8 anchors, but at levels far from the calibration. */
    @Test
    fun `levels far from the calibration keep the gate`() {
        assertThat(reason(evaluate(fit(matched = 4, avgDelta = 9.8)))).isEqualTo("no-signature-anchor")
    }

    /** With the signature anchor heard, nothing changes. */
    @Test
    fun `a fit with its signature anchor is confirmed as before`() {
        val result = evaluate(fit(matched = 4, avgDelta = 9.8, primaryMatched = 1))
        assertThat(result).isInstanceOf(DetectResult.Confirmed::class.java)
    }
}
