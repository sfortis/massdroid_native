package net.asksakis.massdroidv2.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import net.asksakis.massdroidv2.ui.util.BlurTransformation
import kotlin.math.max

/**
 * The widget's version of the full player's backdrop: the album art blurred, under a
 * scrim that fades to the surface colour and a soft vignette. RemoteViews cannot blur
 * or draw gradients, so the whole thing is baked into one bitmap when the card is
 * drawn, from the same 48 px source and six-pass blur the player uses. Text keeps the
 * theme's on-surface colours because the scrim pulls the image toward the surface.
 */
object WidgetBackdrop {

    suspend fun render(context: Context, imageUrl: String, widthPx: Int, heightPx: Int, surfaceArgb: Int, isDark: Boolean): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(SOURCE_PX)
            .allowHardware(false)
            .memoryCacheKey("widget_backdrop_$imageUrl")
            .transformations(BlurTransformation(BLUR_RADIUS))
            .build()
        val blurred = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap ?: return null
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(blurred, coverCrop(blurred, widthPx, heightPx), Rect(0, 0, widthPx, heightPx), paint)
        // A flat scrim, not the player's top-to-bottom gradient: on a card the gradient
        // reads as a blob of colour parked in one corner. Flat tint plus a centred
        // vignette keeps the wash symmetric whatever the card's shape.
        paint.shader = null
        paint.color = withAlpha(surfaceArgb, if (isDark) DARK_SCRIM else LIGHT_SCRIM)
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
        val vignette = if (isDark) DARK_VIGNETTE else LIGHT_VIGNETTE
        paint.shader = RadialGradient(
            widthPx / 2f, heightPx / 2f, max(widthPx, heightPx) * VIGNETTE_RADIUS_SCALE,
            intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, vignette)),
            floatArrayOf(VIGNETTE_CLEAR_STOP, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
        return out
    }

    /** The largest centred window of [src] with the target aspect ratio. */
    private fun coverCrop(src: Bitmap, targetW: Int, targetH: Int): Rect {
        val targetAspect = targetW.toFloat() / targetH
        val srcAspect = src.width.toFloat() / src.height
        return if (srcAspect > targetAspect) {
            val w = (src.height * targetAspect).toInt()
            val x = (src.width - w) / 2
            Rect(x, 0, x + w, src.height)
        } else {
            val h = (src.width / targetAspect).toInt()
            val y = (src.height - h) / 2
            Rect(0, y, src.width, y + h)
        }
    }

    private fun withAlpha(argb: Int, alpha: Float): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /** Same source size and blur as the player's AlbumArtBackdrop. */
    private const val SOURCE_PX = 48
    private const val BLUR_RADIUS = 6

    // Lighter than the player's scrim (0.30/0.62/0.88 and 0.55 vignette): those were tuned
    // for a whole screen with text over the darkest band, and on a card two cells high
    // they left nothing of the image but a tint. Readability comes from the artwork being
    // blurred to a wash plus a moderate pull toward the surface colour.
    private const val DARK_SCRIM = 0.55f
    private const val LIGHT_SCRIM = 0.68f
    private const val DARK_VIGNETTE = 0.45f
    private const val LIGHT_VIGNETTE = 0.25f
    private const val VIGNETTE_CLEAR_STOP = 0.55f
    private const val VIGNETTE_RADIUS_SCALE = 0.78f
}
