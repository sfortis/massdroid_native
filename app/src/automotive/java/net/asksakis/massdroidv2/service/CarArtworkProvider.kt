package net.asksakis.massdroidv2.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import coil.executeBlocking
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Serves real album/artist artwork to the AAOS car media center as PNG bytes over
 * content:// URIs (automotive flavor only; declared in src/automotive/AndroidManifest).
 *
 * The car media center runs in a separate process (com.android.car.media) that
 * cannot reach the Music Assistant image server when it sits behind mTLS / a
 * private network, so a raw https artworkUri silently fails to load (issue #37,
 * "cover art fails to load"). This provider re-fetches the image through the
 * app's own Coil ImageLoader - which carries the mTLS client cert + the shared
 * disk cache - and re-streams the decoded bytes, which the car CAN read across
 * the process boundary via the exported, grant-uri provider.
 *
 * Mirrors the proven [net.asksakis.massdroidv2.auto.PlaceholderArtworkProvider]
 * pipe-streaming pattern; this one fetches the real bytes instead of rendering
 * initials.
 *
 * URI shape: content://<applicationId>.artwork/img?url=<urlencoded-image-url>
 */
class CarArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?
    ): AssetFileDescriptor? = openAsset(uri)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        openAsset(uri)?.parcelFileDescriptor

    private fun openAsset(uri: Uri): AssetFileDescriptor? {
        val bytes = try {
            when (uri.lastPathSegment) {
                "img" -> uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let { loadPng(it) }
                "res" -> uri.getQueryParameter("id")?.toIntOrNull()?.let { renderResource(it) }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "artwork render failed for $uri: ${e.message}")
            null
        } ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "car-artwork-pipe") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } catch (e: IOException) {
                Log.w(TAG, "pipe write failed: ${e.message}")
            }
        }
        return AssetFileDescriptor(pipe[0], 0, bytes.size.toLong())
    }

    private fun loadPng(url: String): ByteArray? {
        val ctx = context ?: return null
        // Coil's singleton loader (MassDroidApp.newImageLoader) reuses the mTLS
        // OkHttp + disk cache, so repeated browse requests hit the cache. allowHardware
        // is off because we read pixels back to PNG-encode for the pipe; a modest
        // cap keeps the streamed bytes small for browse rows.
        val request = ImageRequest.Builder(ctx)
            .data(url)
            .size(ARTWORK_SIZE_PX)
            .allowHardware(false)
            .build()
        val drawable = (ctx.imageLoader.executeBlocking(request) as? SuccessResult)?.drawable
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
    }

    // Renders a (possibly vector) drawable resource to a PNG. The AAOS media center
    // cannot decode a cross-package vector android.resource://, so category icons are
    // served as rasterised bytes instead.
    private fun renderResource(resId: Int): ByteArray? {
        val ctx = context ?: return null
        val drawable = ContextCompat.getDrawable(ctx, resId) ?: return null
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
        drawable.draw(canvas)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "CarArtwork"
        const val ARTWORK_SIZE_PX = 512
        const val ICON_SIZE_PX = 192
        const val PNG_QUALITY = 100
    }
}
