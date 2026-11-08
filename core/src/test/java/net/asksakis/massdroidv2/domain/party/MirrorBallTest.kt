package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/** The Party screen's mirror ball, against the spec the owner picked from the mockups. */
class MirrorBallTest {

    private val tintA = 0xFF1F6F8B.toInt()
    private val tintB = 0xFF8B3A62.toInt()

    private fun brightness(argb: Int) = ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)

    @Test
    fun `bands thin towards the poles and keep at least four tiles`() {
        val counts = (0 until MirrorBall.BANDS).map { MirrorBall.tilesInBand(it) }

        assertThat(counts.max()).isEqualTo(30)
        assertThat(counts.min()).isAtLeast(4)
        assertThat(counts).isEqualTo(counts.reversed())
        assertThat(MirrorBall().tileCount).isEqualTo(counts.sum())
    }

    @Test
    fun `only the half facing the viewer is drawn`() {
        val ball = MirrorBall().apply { update(0f, tintA, tintB) }

        assertThat(ball.visibleCount).isGreaterThan(ball.tileCount / 3)
        assertThat(ball.visibleCount).isLessThan(ball.tileCount * 2 / 3)
        for (i in 0 until ball.visibleCount) {
            val tile = ball.visible[i]
            assertThat(hypot(ball.centers[tile * 2], ball.centers[tile * 2 + 1])).isAtMost(1f)
        }
    }

    @Test
    fun `tiles towards the light are brighter than tiles away from it`() {
        val ball = MirrorBall().apply { update(0f, tintA, tintB) }
        val tiles = (0 until ball.visibleCount).map { ball.visible[it] }
        // The light comes from the upper left, so compare the upper left with the lower right.
        val lit = tiles.filter { ball.centers[it * 2] < -0.3f && ball.centers[it * 2 + 1] > 0.3f }
        val dark = tiles.filter { ball.centers[it * 2] > 0.3f && ball.centers[it * 2 + 1] < -0.3f }

        assertThat(lit.map { brightness(ball.colors[it]) }.average())
            .isGreaterThan(dark.map { brightness(ball.colors[it]) }.average())
    }

    @Test
    fun `some tiles glint as the ball turns`() {
        val ball = MirrorBall()
        val glints = (0 until 36).sumOf { step ->
            ball.update((step * 2 * PI / 36).toFloat(), tintA, tintB)
            (0 until ball.visibleCount).count { ball.specular[ball.visible[it]] > MirrorBall.GLINT_THRESHOLD }
        }

        assertThat(glints).isGreaterThan(0)
    }

    @Test
    fun `the tiles are the same on every visit`() {
        val first = MirrorBall().apply { update(1f, tintA, tintB) }
        val second = MirrorBall().apply { update(1f, tintA, tintB) }

        assertThat(second.colors.toList()).isEqualTo(first.colors.toList())
    }

    @Test
    fun `about a fifth of the tiles carry the tints`() {
        val plain = MirrorBall().apply { update(0f, 0xFF808080.toInt(), 0xFF808080.toInt()) }
        val tinted = MirrorBall().apply { update(0f, 0xFFFF0000.toInt(), 0xFF0000FF.toInt()) }
        val changed = (0 until plain.visibleCount).count {
            val tile = plain.visible[it]
            plain.colors[tile] != tinted.colors[tile]
        }

        assertThat(changed.toDouble() / plain.visibleCount).isWithin(0.1).of(0.2)
    }
}
