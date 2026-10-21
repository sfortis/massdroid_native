package net.asksakis.massdroidv2.service

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import net.asksakis.massdroidv2.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
            .appendQueryParameter("sig", CarArtworkSigner.sign(context, rawUrl, sizePx))
            .build()
    } else {
        Uri.parse(rawUrl)
    }

/**
 * Signs the image addresses this app hands to the car, so the exported [CarArtworkProvider]
 * fetches only what the app itself issued. Without it any app on the head unit could make
 * MassDroid load an arbitrary address with its own HTTP client (client certificate, private
 * network) or a file:// image from its own data, and read the decoded image back.
 *
 * The key is random per install and kept in noBackupFilesDir, so the URIs the media center has
 * cached stay valid after the app's process restarts.
 */
internal object CarArtworkSigner {
    private const val KEY_FILE = "car_artwork_key"
    private const val KEY_BYTES = 32
    private const val SIGNATURE_BYTES = 16
    private const val ALGORITHM = "HmacSHA256"

    @Volatile private var key: ByteArray? = null

    fun sign(context: Context, url: String, sizePx: Int): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key(context), ALGORITHM))
        val digest = mac.doFinal("$url|$sizePx".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            digest.copyOf(SIGNATURE_BYTES),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    fun verify(context: Context, url: String, sizePx: Int, signature: String): Boolean =
        MessageDigest.isEqual(
            sign(context, url, sizePx).toByteArray(Charsets.US_ASCII),
            signature.toByteArray(Charsets.US_ASCII)
        )

    private fun key(context: Context): ByteArray =
        key ?: synchronized(this) { key ?: loadOrCreateKey(context).also { key = it } }

    private fun loadOrCreateKey(context: Context): ByteArray {
        val file = File(context.noBackupFilesDir, KEY_FILE)
        if (file.length() == KEY_BYTES.toLong()) return file.readBytes()
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val temp = File(context.noBackupFilesDir, "$KEY_FILE.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) Log.w("CarArtwork", "could not store the artwork key")
        return bytes
    }
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
