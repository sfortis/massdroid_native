package net.asksakis.massdroidv2.ui.screens.party

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import net.asksakis.massdroidv2.domain.party.MirrorBall
import kotlin.math.roundToInt

/**
 * Draws the Party screen's mirror ball on a canvas. The geometry and the shading come from
 * [MirrorBall]; this only turns its unit coordinates into pixels.
 *
 * It runs on every frame, so it allocates nothing while drawing: one [Path] is reset for each
 * tile, and the glow under each glint is the shared [glow] image scaled into place.
 */
internal class DiscoBall(private val glow: ImageBitmap) {
    private val ball = MirrorBall()
    private val tile = Path()

    /**
     * Draws the ball turned to [angle] radians, centered on [center] with [radius] in pixels,
     * over a disk of [grout] and under the dark [rim].
     */
    fun DrawScope.draw(
        angle: Float,
        center: Offset,
        radius: Float,
        tintA: Color,
        tintB: Color,
        grout: Color,
        rim: Brush,
        glintLength: Float,
        glintStroke: Float
    ) {
        ball.update(angle, tintA.toArgb(), tintB.toArgb())
        drawCircle(grout, radius, center)
        val corners = ball.corners
        for (i in 0 until ball.visibleCount) {
            val index = ball.visible[i]
            val base = index * CORNER_VALUES
            tile.reset()
            tile.moveTo(center.x + radius * corners[base], center.y - radius * corners[base + 1])
            for (corner in 1 until CORNERS) {
                val at = base + corner * 2
                tile.lineTo(center.x + radius * corners[at], center.y - radius * corners[at + 1])
            }
            tile.close()
            drawPath(tile, Color(ball.colors[index]))
        }
        drawCircle(rim, radius, center)
        for (i in 0 until ball.visibleCount) {
            val index = ball.visible[i]
            val spec = ball.specular[index]
            if (spec <= MirrorBall.GLINT_THRESHOLD) continue
            val x = center.x + radius * ball.centers[index * 2]
            val y = center.y - radius * ball.centers[index * 2 + 1]
            val length = glintLength * spec
            val glowRadius = (length * GLOW_SHARE).roundToInt()
            drawImage(
                image = glow,
                dstOffset = IntOffset(x.roundToInt() - glowRadius, y.roundToInt() - glowRadius),
                dstSize = IntSize(glowRadius * 2, glowRadius * 2),
                alpha = (GLOW_ALPHA * spec).coerceAtMost(1f)
            )
            val cross = Color.White.copy(alpha = (CROSS_ALPHA * spec).coerceAtMost(1f))
            drawLine(cross, Offset(x - length, y), Offset(x + length, y), glintStroke)
            drawLine(cross, Offset(x, y - length), Offset(x, y + length), glintStroke)
        }
    }

    private companion object {
        const val CORNERS = 4
        const val CORNER_VALUES = CORNERS * 2
        /** The glow's radius as a share of the glint's arm. */
        const val GLOW_SHARE = 0.6f
        const val GLOW_ALPHA = 0.9f
        const val CROSS_ALPHA = 0.8f
    }
}
