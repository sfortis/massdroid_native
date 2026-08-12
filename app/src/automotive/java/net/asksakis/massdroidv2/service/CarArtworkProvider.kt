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
import android.util.LruCache
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 * URI shape: content://<applicationId>.artwork/img?url=<urlencoded-image-url>
 *
 * ## Why every fetch here is bounded (issue #37, the freeze)
 *
 * `openFile`/`openTypedAssetFile` are called on a **binder thread of this app's
 * process**, and the binder pool is 16 threads. The car media center asks for
 * artwork for every browse row plus the now-playing image concurrently, and a
 * track change mints a fresh now-playing fetch - so burst skipping fires many at
 * once.
 *
 * The original code called `imageLoader.executeBlocking(request)` with no
 * timeout, on a Coil instance built over the WebSocket OkHttp client whose
 * `readTimeout` is 0 (infinite, correct for a long-lived socket). A half-open
 * socket - WiFi to mobile handover, a roaming cell switch - therefore parked that
 * binder thread **forever**. Sixteen of those and the process answers no binder
 * call at all: MediaSession and MediaBrowser are binder, so play/pause/skip/select
 * are all ignored until Force Stop. Audio kept playing because Oboe runs on a
 * real-time HAL thread, which is exactly the reported symptom ("audio fine, UI
 * frozen").
 *
 * Three independent guards, so no network condition can starve the pool again:
 * a hard [FETCH_TIMEOUT_MS] per fetch, a [Semaphore] capping how many binder
 * threads can ever be in here at once, and a cache of the encoded bytes so
 * repeats never touch the network or the encoder. Every rejection returns null
 * fast, which the car renders as a placeholder.
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

    @Suppress("TooGenericExceptionCaught")
    private fun openAsset(uri: Uri): AssetFileDescriptor? {
        val bytes = try {
            when (uri.lastPathSegment) {
                "img" -> uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let {
                    val sizePx = uri.getQueryParameter("size")?.toIntOrNull()
                        ?.coerceIn(MIN_ARTWORK_SIZE_PX, MAX_ARTWORK_SIZE_PX)
                        ?: ARTWORK_SIZE_PX
                    cachedPng(it, sizePx)
                }
                "res" -> uri.getQueryParameter("id")?.toIntOrNull()?.let { renderResource(it) }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "artwork render failed for $uri: ${e.message}")
            null
        } ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        // A shared, bounded pool instead of a thread per request: under burst
        // skipping the old `thread(...)` created unbounded threads, each holding a
        // full PNG in memory.
        pipeExecutor.execute {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } catch (e: IOException) {
                Log.w(TAG, "pipe write failed: ${e.message}")
            }
        }
        return AssetFileDescriptor(pipe[0], 0, bytes.size.toLong())
    }

    /**
     * Encoded PNG for [url] at [sizePx], from cache when possible.
     *
     * The cache is what makes browse scrolling cheap: without it every request
     * re-decoded the image and re-encoded it at quality 100 (up to 1024px) on the
     * binder thread, for rows the car had already asked about seconds earlier.
     */
    private fun cachedPng(url: String, sizePx: Int): ByteArray? {
        val key = "$url|$sizePx"
        pngCache.get(key)?.let { return it }
        val bytes = loadPng(url, sizePx) ?: return null
        pngCache.put(key, bytes)
        return bytes
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadPng(url: String, sizePx: Int): ByteArray? {
        val ctx = context ?: return null
        // Never let artwork own more than ARTWORK_CONCURRENCY of the 16 binder
        // threads. Refusing immediately is the right answer: the car draws a
        // placeholder, and the session stays responsive.
        if (!fetchSlots.tryAcquire(SLOT_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "artwork busy (${inFlight.get()} in flight), refusing $url")
            return null
        }
        inFlight.incrementAndGet()
        try {
            // allowHardware is off because we read pixels back to PNG-encode for
            // the pipe. Browse rows ask for a modest edge; the now-playing screen
            // asks for a larger one (the AAOS media center centers without
            // upscaling, so a small source renders tiny there).
            val request = ImageRequest.Builder(ctx)
                .data(url)
                .size(sizePx)
                .allowHardware(false)
                .build()
            // Bounded wait. `executeBlocking` had no upper bound at all, which is
            // what turned one dead socket into a permanently frozen app.
            val result = runBlocking {
                withTimeout(FETCH_TIMEOUT_MS) { ctx.imageLoader.execute(request) }
            }
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                ?: return null
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "artwork fetch timed out after ${FETCH_TIMEOUT_MS}ms: $url")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "artwork fetch failed for $url: ${e.message}")
            return null
        } finally {
            inFlight.decrementAndGet()
            fetchSlots.release()
        }
    }

    // Renders a (possibly vector) drawable resource to a PNG. The AAOS media center
    // cannot decode a cross-package vector android.resource://, so category icons are
    // served as rasterised bytes instead.
    private fun renderResource(resId: Int): ByteArray? {
        val ctx = context ?: return null
        val key = "res:$resId"
        pngCache.get(key)?.let { return it }
        val drawable = ContextCompat.getDrawable(ctx, resId) ?: return null
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
        drawable.draw(canvas)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
        pngCache.put(key, bytes)
        return bytes
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
        const val MIN_ARTWORK_SIZE_PX = 64
        const val MAX_ARTWORK_SIZE_PX = 1024
        const val ICON_SIZE_PX = 192
        const val PNG_QUALITY = 100

        /**
         * Upper bound on one artwork fetch. Long enough for a slow-but-alive
         * connection, short enough that a dead one frees the binder thread while
         * the car is still on the same screen.
         */
        const val FETCH_TIMEOUT_MS = 8_000L

        /**
         * Binder threads artwork may occupy at once, out of the pool of 16. The
         * rest stay free for MediaSession/MediaBrowser, which is the whole point:
         * transport controls must answer even when every image is timing out.
         */
        const val ARTWORK_CONCURRENCY = 4

        /** Brief wait for a slot before giving up; a placeholder now beats art later. */
        const val SLOT_WAIT_MS = 250L

        /** Encoded PNGs. 1024px art is ~1-2 MB, so this holds a browse screen's worth. */
        const val PNG_CACHE_BYTES = 24 * 1024 * 1024

        val fetchSlots = Semaphore(ARTWORK_CONCURRENCY, true)
        val inFlight = AtomicInteger(0)

        val pipeExecutor = Executors.newFixedThreadPool(ARTWORK_CONCURRENCY) { r ->
            Thread(r, "car-artwork-pipe").apply { isDaemon = true }
        }

        val pngCache = object : LruCache<String, ByteArray>(PNG_CACHE_BYTES) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }
    }
}
