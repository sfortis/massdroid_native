package net.asksakis.massdroidv2.service

import android.content.Context
import android.net.Uri
import net.asksakis.massdroidv2.BuildConfig

/**
 * Artwork URI helpers for the car (AAOS) build.
 *
 * The AAOS media center renders MediaItem/MediaMetadata `artworkUri` (it loads
 * the URI itself in its own process) and ignores the embedded `artworkData`
 * bytes that work fine for projected Android Auto. That separate process also
 * cannot reach the (mTLS / private-network) MA image server, nor reliably decode
 * a vector `android.resource://` from our package. So in the automotive flavor we
 * route every car-facing artwork URI through [CarArtworkProvider], which streams
 * real PNG bytes the car can read across the process boundary.
 *
 * On phone / Android Auto / TV these return the original URI unchanged, so those
 * paths are byte-for-byte identical to before.
 */

private fun artworkAuthority(context: Context): String = context.packageName + ".artwork"

/** Default decoded edge for browse-row artwork (modest, keeps streamed bytes small). */
const val CAR_ARTWORK_BROWSE_PX = 512

/**
 * Larger decoded edge for the now-playing artwork. The AAOS media center centers
 * (does not upscale) the now-playing image, so a modest source renders small on a
 * high-density car display; Google recommends >=320dp. [CarArtworkProvider] clamps.
 */
const val CAR_ARTWORK_NOW_PLAYING_PX = 1024

/**
 * Remote image URL -> content:// (car) or the raw URL (phone/TV/AA). [sizePx] hints
 * the decoded edge the car provider should produce (browse rows vs now-playing).
 */
fun carArtworkUri(context: Context, rawUrl: String, sizePx: Int = CAR_ARTWORK_BROWSE_PX): Uri =
    if (BuildConfig.IS_AUTOMOTIVE) {
        Uri.Builder()
            .scheme("content")
            .authority(artworkAuthority(context))
            .appendPath("img")
            .appendQueryParameter("url", rawUrl)
            .appendQueryParameter("size", sizePx.toString())
            .build()
    } else {
        Uri.parse(rawUrl)
    }

/** Drawable resource -> content:// PNG render (car) or android.resource:// (phone/TV/AA). */
fun carIconUri(context: Context, resId: Int): Uri =
    if (BuildConfig.IS_AUTOMOTIVE) {
        Uri.Builder()
            .scheme("content")
            .authority(artworkAuthority(context))
            .appendPath("res")
            .appendQueryParameter("id", resId.toString())
            .build()
    } else {
        Uri.Builder()
            .scheme("android.resource")
            .authority(context.packageName)
            .appendPath(resId.toString())
            .build()
    }
