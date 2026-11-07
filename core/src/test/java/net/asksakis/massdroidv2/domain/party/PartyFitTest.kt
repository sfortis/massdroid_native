package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.BALL_TOP_DP
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.COMPACT_TOP_DP
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.QR_MAX_DP
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.QR_MIN_DP
import org.junit.Test

/** The Party screen fits on one screen, and the QR code gives way before the ball does. */
class PartyFitTest {

    @Test
    fun `a tall phone gets the full QR code and the full room for the ball`() {
        val fit = PartyFit.withQr(availableDp = 650f, belowDp = 212f, widthDp = 312f)

        assertThat(fit).isEqualTo(PartyFit(BALL_TOP_DP, QR_MAX_DP, showBall = true))
    }

    @Test
    fun `the QR code shrinks before the ball's room does`() {
        val fit = PartyFit.withQr(availableDp = 600f, belowDp = 212f, widthDp = 312f)

        assertThat(fit.topDp).isEqualTo(BALL_TOP_DP)
        assertThat(fit.qrDp).isEqualTo(204f)
        assertThat(fit.showBall).isTrue()
    }

    @Test
    fun `with the QR code at its smallest the ball's room shrinks`() {
        val fit = PartyFit.withQr(availableDp = 650f, belowDp = 300f, widthDp = 312f)

        assertThat(fit).isEqualTo(PartyFit(170f, QR_MIN_DP, showBall = true))
    }

    /**
     * The worst case: a 360 x 640 dp phone with three-button navigation leaves about 504 dp
     * under the app bar, and the content under the code is about 300 dp tall with the warning
     * and the home network hint. The ball is left out and everything still fits.
     */
    @Test
    fun `the worst case on a 360 by 640 phone fits without the ball`() {
        val fit = PartyFit.withQr(availableDp = 504f, belowDp = 300f, widthDp = 312f)

        assertThat(fit.showBall).isFalse()
        assertThat(fit.topDp).isEqualTo(COMPACT_TOP_DP)
        assertThat(fit.qrDp).isAtLeast(QR_MIN_DP)
        assertThat(fit.topDp + fit.qrDp + 300f).isAtMost(504f)
    }

    @Test
    fun `a narrow screen caps the QR code at its width`() {
        val fit = PartyFit.withQr(availableDp = 900f, belowDp = 200f, widthDp = 150f)

        assertThat(fit.qrDp).isEqualTo(150f)
    }

    @Test
    fun `a notice keeps the ball when it fits under it`() {
        assertThat(PartyFit.withoutQr(availableDp = 600f, contentDp = 250f).showBall).isTrue()
        assertThat(PartyFit.withoutQr(availableDp = 300f, contentDp = 250f))
            .isEqualTo(PartyFit(COMPACT_TOP_DP, 0f, showBall = false))
    }
}
