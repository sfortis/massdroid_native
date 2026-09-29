package net.asksakis.massdroidv2.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Lays a fine grain over the content, the way a matte finish breaks up a painted surface.
 *
 * A large area of one colour, which is what a blurred album cover becomes once the scrim is
 * over it, shows banding where the gradient steps between two neighbouring shades. The grain
 * hides those steps by scattering the boundary, and it gives the background something to
 * catch the eye that is not the artwork, so the surface reads as a material rather than as
 * flat fill.
 *
 * The noise is a single tile repeated across the area. It is built once for the process and
 * drawn by a shader in the draw phase, so it costs no recomposition, no allocation per frame
 * and nothing at all on a frame that does not redraw this surface.
 *
 * The grain both lifts and darkens: half the dots are white and half are black, in equal
 * measure, so the average brightness of what is underneath is left where it was and only its
 * evenness is broken.
 *
 * @param alpha how strongly the grain is laid on. Keep it low: this is a texture, not a
 *   layer, and past about 0.1 it starts to read as a dirty screen.
 * @param dotSize the size of one grain dot. Given in dp rather than pixels so the texture
 *   feels the same on a dense screen as on a coarse one, instead of turning invisible.
 */
fun Modifier.grain(alpha: Float, dotSize: Dp = GRAIN_DOT_SIZE): Modifier = drawWithCache {
    val brush = ShaderBrush(
        ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated).apply {
            // One tile pixel becomes one dot of the requested size. Without this the dots
            // are single device pixels, which on a 3x screen is too fine to read as a
            // texture at all.
            val scale = dotSize.toPx().coerceAtLeast(1f)
            setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        }
    )
    onDrawWithContent {
        drawContent()
        drawRect(brush = brush, alpha = alpha)
    }
}

/** Edge of the repeated noise tile, in dots. Large enough that the repeat is not a pattern. */
private const val GRAIN_TILE_DOTS = 128

/**
 * A fixed seed, so every surface in the app carries the same grain and a screen redrawn
 * after a process restart is the screen the listener left.
 */
private const val GRAIN_SEED = 0x5A6752

/** Default dot, a touch under one device pixel on a 2x screen and one and a half on a 3x. */
private val GRAIN_DOT_SIZE = 0.5.dp

/**
 * The noise itself, built once and shared. A dot is either white or black at a random
 * opacity, and the two are drawn in equal numbers so the grain does not tint what it covers.
 */
private val grainTile by lazy {
    val random = Random(GRAIN_SEED)
    val dots = IntArray(GRAIN_TILE_DOTS * GRAIN_TILE_DOTS) {
        val shade = if (random.nextBoolean()) 0x00FFFFFF else 0x00000000
        val opacity = random.nextInt(0x100) shl 24
        opacity or shade
    }
    Bitmap.createBitmap(GRAIN_TILE_DOTS, GRAIN_TILE_DOTS, Bitmap.Config.ARGB_8888)
        .apply { setPixels(dots, 0, GRAIN_TILE_DOTS, 0, 0, GRAIN_TILE_DOTS, GRAIN_TILE_DOTS) }
        .asImageBitmap()
}
