package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the Length slider, the one knob whose promise is a NUMBER the listener
 * reads off the screen: "~N tracks".
 *
 * The settings screen carried its own copy of `20 + length * 40` while the build
 * used the constants. They agreed by luck, and nothing would have failed if they
 * stopped - the label would simply have started lying. Both now call
 * [smartMixTrackTargetFor], and these tests pin the numbers that label shows.
 */
class SmartMixLengthTest {

    @Test
    fun `the slider ends are the advertised range`() {
        assertThat(smartMixTrackTargetFor(0.0)).isEqualTo(20)
        assertThat(smartMixTrackTargetFor(1.0)).isEqualTo(60)
    }

    @Test
    fun `the middle is proportional`() {
        assertThat(smartMixTrackTargetFor(0.5)).isEqualTo(40)
        // The user's own setting at the time of writing.
        assertThat(smartMixTrackTargetFor(0.33)).isEqualTo(33)
    }

    @Test
    fun `the target never decreases as the slider rises`() {
        var previous = 0
        for (step in 0..100) {
            val target = smartMixTrackTargetFor(step / 100.0)
            assertThat(target).isAtLeast(previous)
            previous = target
        }
    }

    @Test
    fun `a slider value outside 0 to 1 is clamped, not extrapolated`() {
        // A corrupt DataStore value must not ask the engine for a 400-track mix.
        assertThat(smartMixTrackTargetFor(-1.0)).isEqualTo(20)
        assertThat(smartMixTrackTargetFor(9.0)).isEqualTo(60)
    }

    @Test
    fun `NaN falls back to the shortest mix rather than to no mix at all`() {
        // coerceIn does not sanitize NaN: both of its comparisons are false, so
        // it passes through and NaN.toInt() is 0. Without the explicit guard a
        // corrupt stored value asks for zero tracks, and buildFromCandidates
        // returns an empty list for target <= 0 - a Smart Mix that silently
        // produces nothing.
        assertThat(smartMixTrackTargetFor(Double.NaN)).isEqualTo(20)
    }

    @Test
    fun `a value that came through a Float slider still lands on a sane target`() {
        // The slider is Float and DataStore stores Float, so the engine never
        // sees a clean 0.7: it sees 0.699999988. Truncation can then shift a
        // boundary down by one. That is accepted (the label says "~"), but the
        // UI and the engine must agree, which they do because both call this.
        assertThat(smartMixTrackTargetFor(0.7f.toDouble())).isEqualTo(47)
        assertThat(smartMixTrackTargetFor(0.7f.toDouble()))
            .isEqualTo(smartMixTrackTargetFor(0.7f.toDouble()))
    }
}
