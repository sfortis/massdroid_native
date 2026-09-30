package net.asksakis.massdroidv2.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.util.Log
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "AnimatedImage"

/**
 * Plays an animated WebP from the app's own assets, looping.
 *
 * Drawn through the platform's `AnimatedImageDrawable` rather than through Coil, which
 * would need the separate GIF artifact to decode an animation, for a single decorative
 * clip. Nothing is downloaded and nothing is cached: the frames are in the APK.
 *
 * The file is named rather than referenced as a resource because the release notes name
 * it, and the notes are written without touching Kotlin.
 *
 * Below API 28 there is no `ImageDecoder` and so no animation. The composable draws
 * nothing there rather than a frozen frame, and callers are expected to read fine without
 * it, because a picture that stopped moving says less than no picture at all.
 */
@Composable
fun AnimatedImage(
    assetPath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            ImageView(viewContext).apply {
                this.contentDescription = contentDescription
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { view ->
            // Decoded in update rather than in factory so a file swap is picked up, and
            // guarded because a missing, corrupt or unsupported file throws rather than
            // returning null, and a decorative animation must not take a screen down.
            val drawable = runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.assets, assetPath)
                )
            }.getOrElse {
                Log.w(TAG, "Could not decode animation $assetPath: ${it.message}")
                null
            }
            view.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                start()
            }
        },
        onRelease = { view ->
            (view.drawable as? AnimatedImageDrawable)?.stop()
            view.setImageDrawable(null)
        }
    )
}
