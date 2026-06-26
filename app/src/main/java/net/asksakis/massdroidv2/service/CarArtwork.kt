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

/** Remote image URL -> content:// (car) or the raw URL (phone/TV/AA). */
fun carArtworkUri(context: Context, rawUrl: String): Uri =
    if (BuildConfig.IS_AUTOMOTIVE) {
        Uri.Builder()
            .scheme("content")
            .authority(artworkAuthority(context))
            .appendPath("img")
            .appendQueryParameter("url", rawUrl)
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
