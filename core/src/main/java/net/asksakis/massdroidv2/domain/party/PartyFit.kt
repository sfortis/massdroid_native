package net.asksakis.massdroidv2.domain.party

import kotlin.math.min

/**
 * How the Party screen fits the mirror ball and the QR code into the height it has, so that
 * the screen needs no scrolling on a normal phone. All sizes are in dp.
 *
 * [topDp] is the room above the QR code, under the app bar, and [showBall] says whether that
 * room holds the ball. The QR code gives way first: it shrinks from [QR_MAX_DP] towards
 * [QR_MIN_DP] before the ball's room shrinks. When the room left is too small for the ball,
 * the ball is left out and the content starts right under the app bar.
 */
data class PartyFit(val topDp: Float, val qrDp: Float, val showBall: Boolean) {

    companion object {
        /** A 220 dp code still scans easily from arm's length. */
        const val QR_MAX_DP = 220f
        const val QR_MIN_DP = 180f

        /** The full room for the ball above the content. */
        const val BALL_TOP_DP = 184f

        /** The least room in which the ball still reads as a ball. */
        const val MIN_BALL_TOP_DP = 120f
        const val COMPACT_TOP_DP = 16f

        /**
         * The fit for a QR code above content [belowDp] tall, in [availableDp] of height and
         * [widthDp] of width. [belowDp] includes every gap and padding under the code. When
         * even the compact fit is taller than [availableDp], the screen scrolls.
         */
        fun withQr(availableDp: Float, belowDp: Float, widthDp: Float): PartyFit {
            val qrMax = min(QR_MAX_DP, widthDp)
            val qrMin = min(QR_MIN_DP, qrMax)
            val room = availableDp - belowDp
            val qrWithBall = (room - BALL_TOP_DP).coerceIn(qrMin, qrMax)
            val ballTop = min(room - qrWithBall, BALL_TOP_DP)
            if (ballTop >= MIN_BALL_TOP_DP) return PartyFit(ballTop, qrWithBall, showBall = true)
            val qr = (room - COMPACT_TOP_DP).coerceIn(qrMin, qrMax)
            return PartyFit(COMPACT_TOP_DP, qr, showBall = false)
        }

        /** The fit for a notice [contentDp] tall, with no QR code. */
        fun withoutQr(availableDp: Float, contentDp: Float): PartyFit {
            val ballTop = min(availableDp - contentDp, BALL_TOP_DP)
            return if (ballTop >= MIN_BALL_TOP_DP) {
                PartyFit(ballTop, qrDp = 0f, showBall = true)
            } else {
                PartyFit(COMPACT_TOP_DP, qrDp = 0f, showBall = false)
            }
        }
    }
}
