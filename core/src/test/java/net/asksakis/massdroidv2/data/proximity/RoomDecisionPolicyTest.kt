package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The signature-anchor gate and its one exception: a room that is the only candidate in
 * the scan, with other BLE rooms configured and none of their anchors heard. The fits replay
 * the office arrival of 2026-11-11, where the room's signature anchor (a battery thermometer)
 * never advertised, every home room matched 0 of 8, and every fit was held back.
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

    private val officeAnchors = setOf("o1", "o2", "o3", "o4", "o5", "o6", "o7", "o8")
    private val livingRoomAnchors = setOf("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8")
    private val bothRooms = mapOf("office" to officeAnchors, "living" to livingRoomAnchors)

    private fun evaluate(
        fit: RoomFit,
        runnerUpPresent: Boolean = false,
        otherRooms: OtherRoomsHeard = OtherRoomsHeard.SILENT
    ): DetectResult =
        RoomDecisionPolicy.evaluate(
            winnerRoom = office,
            winner = fit,
            runnerUpPresent = runnerUpPresent,
            otherRooms = otherRooms,
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

    /**
     * With one BLE room, nothing shows that the phone is not next to it (a stairwell, a
     * neighbour), so the gate stays on every fit.
     */
    @Test
    fun `a single BLE room keeps the gate`() {
        val heard = officeAnchors.take(4).toSet()
        val otherRooms = RoomDecisionPolicy.otherRoomsHeard("office", heard, mapOf("office" to officeAnchors))
        assertThat(otherRooms).isEqualTo(OtherRoomsHeard.NONE_CONFIGURED)
        assertThat(reason(evaluate(fit(matched = 4, avgDelta = 6.2), otherRooms = otherRooms)))
            .isEqualTo("no-signature-anchor")
    }

    /**
     * A neighbouring room heard on one anchor stays below its coverage minimum and never
     * becomes a runner-up, but it still shows that the phone may be next door.
     */
    @Test
    fun `another room heard below its coverage minimum keeps the gate`() {
        val heard = officeAnchors.take(4).toSet() + "l3"
        val otherRooms = RoomDecisionPolicy.otherRoomsHeard("office", heard, bothRooms)
        assertThat(otherRooms).isEqualTo(OtherRoomsHeard.HEARD)
        assertThat(reason(evaluate(fit(matched = 4, avgDelta = 6.2), otherRooms = otherRooms)))
            .isEqualTo("no-signature-anchor")
    }

    @Test
    fun `other rooms with none of their anchors heard are silent`() {
        val heard = officeAnchors.take(4).toSet() + "wifi:office-ap"
        assertThat(RoomDecisionPolicy.otherRoomsHeard("office", heard, bothRooms))
            .isEqualTo(OtherRoomsHeard.SILENT)
    }

    /** 12:34:20: 2 of 8 anchors, still walking in. Too little evidence. */
    @Test
    fun `fewer than half the anchors keep the gate`() {
        assertThat(reason(evaluate(fit(matched = 2, avgDelta = 6.0)))).isEqualTo("no-signature-anchor")
    }

    @Test
    fun `three of eight anchors keep the gate`() {
        assertThat(reason(evaluate(fit(matched = 3, avgDelta = 6.0)))).isEqualTo("no-signature-anchor")
    }

    @Test
    fun `levels exactly at the strong-fit delta pass`() {
        assertThat(evaluate(fit(matched = 4, avgDelta = 8.0))).isInstanceOf(DetectResult.Confirmed::class.java)
    }

    @Test
    fun `levels just above the strong-fit delta keep the gate`() {
        assertThat(reason(evaluate(fit(matched = 4, avgDelta = 8.1)))).isEqualTo("no-signature-anchor")
    }

    /** The log line names the exception only when it is what lets the fit past the gate. */
    @Test
    fun `the exception is reported only when the fit lacks its signature anchor`() {
        val silent = OtherRoomsHeard.SILENT
        assertThat(RoomDecisionPolicy.soleCandidateWaivesSignatureGate(fit(4, 6.2), false, silent, true)).isTrue()
        assertThat(RoomDecisionPolicy.soleCandidateWaivesSignatureGate(fit(4, 6.2, primaryMatched = 1), false, silent, true))
            .isFalse()
        assertThat(RoomDecisionPolicy.soleCandidateWaivesSignatureGate(fit(4, 6.2), false, OtherRoomsHeard.HEARD, true))
            .isFalse()
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
