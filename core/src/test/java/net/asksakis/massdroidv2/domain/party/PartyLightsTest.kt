package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The strobe and the specks behind the Party screen. */
class PartyLightsTest {

    @Test
    fun `the strobe peaks at 3 and 53 percent of each second`() {
        assertThat(PartyLights.strobeAlpha(30, 0.18f, 0.11f)).isWithin(1e-6f).of(0.18f)
        assertThat(PartyLights.strobeAlpha(530, 0.18f, 0.11f)).isWithin(1e-6f).of(0.11f)
        assertThat(PartyLights.strobeAlpha(1_030, 0.18f, 0.11f)).isWithin(1e-6f).of(0.18f)
    }

    @Test
    fun `the strobe is dark between the flashes`() {
        listOf(0L, 90L, 300L, 500L, 590L, 900L).forEach {
            assertThat(PartyLights.strobeAlpha(it, 0.18f, 0.11f)).isEqualTo(0f)
        }
    }

    /** WCAG 2.3.1 allows no more than three flashes in any second. */
    @Test
    fun `the strobe never flashes more than twice a second`() {
        val samples = (0 until 3_000).map { PartyLights.strobeAlpha(it.toLong(), 0.45f, 0.28f) > 0f }
        val flashes = samples.indices.count { it > 0 && samples[it] && !samples[it - 1] }

        assertThat(flashes).isAtMost(6)
    }

    @Test
    fun `a speck fades in, holds and fades out`() {
        assertThat(PartySpecks.opacity(0f)).isEqualTo(0f)
        assertThat(PartySpecks.opacity(0.5f)).isWithin(1e-6f).of(0.9f)
        assertThat(PartySpecks.opacity(1f)).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `specks stay on screen and keep their cycle`() {
        val specks = PartySpecks()

        assertThat(specks.count).isEqualTo(18)
        for (i in 0 until specks.count) {
            assertThat(specks.startX[i]).isIn(com.google.common.collect.Range.closed(0.1f, 1f))
            assertThat(specks.startY[i]).isIn(com.google.common.collect.Range.closed(0f, 0.95f))
            assertThat(specks.progress(i, 123_456)).isIn(com.google.common.collect.Range.closedOpen(0f, 1f))
        }
    }
}
