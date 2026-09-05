package net.asksakis.massdroidv2.ui.util

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Blurs a decoded image once, when it is loaded, instead of on every frame.
 *
 * `Modifier.blur` was the obvious way to soften the now-playing backdrop, and it was
 * measurably the wrong one: it re-runs a full-screen render effect every frame to blur
 * an image that does not change until the track does. On a 120 Hz phone that put 661
 * of 679 janky frames in "slow issue draw commands" and pushed the median frame to
 * 13 ms against a budget of 8.3. Blurring the bitmap at load time leaves the draw phase
 * with nothing but a scaled bitmap, and it works below API 31, where `Modifier.blur`
 * silently does nothing at all.
 *
 * The blur is three box passes, which approximates a Gaussian closely enough that the
 * eye cannot tell, at a fraction of the cost. Inputs here are tiny (the caller decodes
 * at a few dozen pixels because the result is stretched over a whole screen anyway), so
 * the whole operation is a fraction of a millisecond on the decode thread.
 */
class BlurTransformation(private val radius: Int = DEFAULT_RADIUS) : Transformation {

    override val cacheKey: String = "${javaClass.name}-$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (radius < 1 || input.width < 2 || input.height < 2) return input
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        repeat(BOX_PASSES) {
            boxBlurHorizontal(pixels, w, h, radius)
            boxBlurVertical(pixels, w, h, radius)
        }

        val output = createBitmap(w, h)
        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    private fun boxBlurHorizontal(pixels: IntArray, w: Int, h: Int, r: Int) {
        val row = IntArray(w)
        for (y in 0 until h) {
            val base = y * w
            System.arraycopy(pixels, base, row, 0, w)
            for (x in 0 until w) {
                var a = 0
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (k in (x - r)..(x + r)) {
                    // Clamp instead of skipping, so the edges keep their weight rather
                    // than fading towards transparent.
                    val c = row[k.coerceIn(0, w - 1)]
                    a += (c ushr 24) and 0xFF
                    red += (c ushr 16) and 0xFF
                    green += (c ushr 8) and 0xFF
                    blue += c and 0xFF
                    count++
                }
                pixels[base + x] = pack(a / count, red / count, green / count, blue / count)
            }
        }
    }

    private fun boxBlurVertical(pixels: IntArray, w: Int, h: Int, r: Int) {
        val column = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) column[y] = pixels[y * w + x]
            for (y in 0 until h) {
                var a = 0
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (k in (y - r)..(y + r)) {
                    val c = column[k.coerceIn(0, h - 1)]
                    a += (c ushr 24) and 0xFF
                    red += (c ushr 16) and 0xFF
                    green += (c ushr 8) and 0xFF
                    blue += c and 0xFF
                    count++
                }
                pixels[y * w + x] = pack(a / count, red / count, green / count, blue / count)
            }
        }
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    override fun equals(other: Any?): Boolean = other is BlurTransformation && other.radius == radius

    override fun hashCode(): Int = radius

    companion object {
        private const val DEFAULT_RADIUS = 4

        /** Three box passes approximate a Gaussian; more adds cost without a visible gain. */
        private const val BOX_PASSES = 3
    }
}
