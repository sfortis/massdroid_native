package net.asksakis.massdroidv2.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a shadow behind the content in the shape given, the way CSS `box-shadow` does.
 *
 * The platform's elevation shadow is a different thing. It wraps the shape evenly, its
 * blur is tied to the depth, and its offset cannot be set, so a design written as
 * `0 8px 24px rgba(0, 0, 0, 0.25)` cannot be expressed with it. This one takes the blur
 * and the offset separately and reproduces such a design as it stands.
 *
 * It is drawn rather than lifted, which is what makes it usable on something that moves.
 * A `graphicsLayer` above it in the chain transforms it along with the content, so it
 * travels, scales and fades with what it belongs to instead of staying behind.
 *
 * @param shape the outline to cast, normally the same shape the content is clipped to.
 * @param color the shade, alpha included.
 * @param blurRadius read the way CSS reads it: the shade reaches about half of it beyond
 *   the shape's edge. Leave room for that much around the content or it will be clipped.
 * @param offsetY how far down the shade falls.
 * @param offsetX how far sideways the shade falls.
 */
fun Modifier.dropShadow(
    shape: Shape,
    color: Color,
    blurRadius: Dp,
    offsetY: Dp = 0.dp,
    offsetX: Dp = 0.dp
): Modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
            translate(Offset(offsetX.toPx(), offsetY.toPx()))
        }
        val paint = Paint().apply {
            this.color = color
            asFrameworkPaint().maskFilter = blurMaskFilterFor(blurRadius.toPx())
        }
        onDrawBehind { drawIntoCanvas { canvas -> canvas.drawPath(path, paint) } }
    }
} else {
    // `BlurMaskFilter` is only honoured on a hardware canvas from API 28. Below that the
    // platform shadow stands in, which is what this replaced and still reads as a lift.
    shadow(elevation = blurRadius * FALLBACK_ELEVATION_FRACTION, shape = shape)
}

/**
 * The mask filter for a CSS blur radius, or none when the radius rounds away to nothing.
 *
 * CSS defines its blur radius as twice the standard deviation of the blur, while Skia
 * derives the deviation from a mask filter's radius as `0.57735 * radius + 0.5`. Inverting
 * that is what makes a value copied from a stylesheet land on the same blur here.
 */
private fun blurMaskFilterFor(cssBlurPx: Float): BlurMaskFilter? {
    val sigma = cssBlurPx / 2f
    val radius = (sigma - SKIA_SIGMA_OFFSET) / SKIA_SIGMA_SCALE
    return if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
}

private const val SKIA_SIGMA_SCALE = 0.57735f
private const val SKIA_SIGMA_OFFSET = 0.5f

/** Depth for the platform shadow below API 28, where half the blur reads about the same. */
private const val FALLBACK_ELEVATION_FRACTION = 0.5f
