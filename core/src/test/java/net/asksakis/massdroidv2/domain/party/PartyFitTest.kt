package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.BALL_TOP_DP
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.COMPACT_TOP_DP
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.GAP_COUNT
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.MAX_EXTRA_GAP_DP
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.QR_MAX_DP
import net.asksakis.massdroidv2.domain.party.PartyFit.Companion.QR_MIN_DP
import org.junit.Test

/** The Party screen fits on one screen, and the QR code gives way before the ball does. */
class PartyFitTest {

    @Test
    fun `a tall phone gets the full QR code and the full room for the ball`() {
        val fit = PartyFit.withQr(availableDp = 650f, belowDp = 212f, widthDp = 312f)

        assertThat(fit.topDp).isEqualTo(BALL_TOP_DP)
        assertThat(fit.qrDp).isEqualTo(QR_MAX_DP)
        assertThat(fit.showBall).isTrue()
    }

    /** 438 dp of room, 184 for the ball and 220 for the code leave 34 dp for three gaps. */
    @Test
    fun `spare height is spread over the gaps under the code`() {
        val fit = PartyFit.withQr(availableDp = 650f, belowDp = 212f, widthDp = 312f)

        assertThat(fit.gapDp).isWithin(0.01f).of(34f / GAP_COUNT)
        assertThat(fit.topDp + fit.qrDp + 212f + fit.gapDp * GAP_COUNT).isWithin(0.01f).of(650f)
    }

    @Test
    fun `the extra gaps stop at their maximum`() {
        assertThat(PartyFit.withQr(availableDp = 900f, belowDp = 212f, widthDp = 312f).gapDp)
            .isEqualTo(MAX_EXTRA_GAP_DP)
    }

    @Test
    fun `the gaps give up their extra before the QR code shrinks`() {
        val fit = PartyFit.withQr(availableDp = 600f, belowDp = 212f, widthDp = 312f)

        assertThat(fit.gapDp).isEqualTo(0f)
        assertThat(fit.qrDp).isLessThan(QR_MAX_DP)
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

        assertThat(fit).isEqualTo(PartyFit(170f, QR_MIN_DP, showBall = true, gapDp = 0f))
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
        assertThat(fit.gapDp).isEqualTo(0f)
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
