package net.asksakis.massdroidv2.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Serves artwork bytes to AAOS Media Center (and any other MediaBrowser
 * client) over a content:// URI.
 *
 * The car media center will not fetch arbitrary HTTPS artwork URLs by
 * itself (it logs `BitmapDrawable created with null Bitmap` and renders
 * empty tiles). Wrapping our URLs in a content URI makes the car app call
 * back into our process via Binder, where we can fetch through the
 * existing OkHttp client (which already has mTLS / network policy
 * configured) and stream the bytes back. This is the same pattern used by
 * Spotify, YouTube Music and AAOS's own LocalMediaPlayer for art that
 * isn't already on disk.
 *
 * URI shape: `content://<authority>/img?url=<encoded-https-url>`
 *
 * A small in-memory byte cache keeps repeated requests for the same URL
 * cheap; the AAOS browse view frequently re-asks for the same album icon
 * during scroll.
 */
class ArtworkContentProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ArtworkProviderEntryPoint {
        fun okHttpClient(): OkHttpClient
    }

    private val httpClient: OkHttpClient by lazy {
        val ctx = context?.applicationContext
            ?: error("ContentProvider context unavailable")
        // Reuse the app's OkHttpClient so mTLS, cookie jar, DNS, and network
        // policy match the rest of the app.
        EntryPointAccessors.fromApplication(ctx, ArtworkProviderEntryPoint::class.java)
            .okHttpClient()
            .newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val byteCache = ConcurrentHashMap<String, ByteArray>()

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = "image/*"

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?
    ): AssetFileDescriptor? = openArtwork(uri)

    @Suppress("UnusedParameter")
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        openArtwork(uri)?.parcelFileDescriptor

    @Suppress("TooGenericExceptionCaught")
    private fun openArtwork(uri: Uri): AssetFileDescriptor? {
        val url = uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "openArtwork: missing url param: $uri")
                return null
            }
        val pipe = ParcelFileDescriptor.createPipe()
        // Fetch happens on a worker thread because openFile/openTypedAssetFile is invoked on the
        // caller's main thread; OkHttp's blocking execute() would otherwise hit
        // NetworkOnMainThreadException. Bytes are streamed directly into the pipe so the consumer
        // (AA / MediaSession's BitmapLoader) sees a normal InputStream.
        thread(name = "artwork-fetch") {
            try {
                val cached = byteCache[url]
                val bytes = cached ?: run {
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "openArtwork: HTTP ${response.code} for $url")
                            return@run null
                        }
                        response.body?.bytes()
                    }
                } ?: run {
                    pipe[1].closeWithError("artwork fetch failed")
                    return@thread
                }
                if (cached == null) {
                    if (byteCache.size > MAX_CACHE_ENTRIES) byteCache.clear()
                    byteCache[url] = bytes
                }
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } catch (e: Exception) {
                Log.w(TAG, "openArtwork: fetch failed for $url", e)
                try { pipe[1].closeWithError(e.message ?: "error") } catch (_: IOException) {}
            }
        }
        return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    // The following are unused for an asset-only provider but must be present.
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

    companion object {
        private const val TAG = "ArtworkProvider"
        private const val MAX_CACHE_ENTRIES = 256

        /**
         * Build the content URI that wraps a remote artwork URL. The
         * authority comes from the manifest's `applicationId.artwork`
         * pattern so it stays unique between debug and release builds.
         */
        fun wrap(authority: String, url: String): Uri =
            Uri.parse("content://$authority/img").buildUpon()
                .appendQueryParameter("url", url)
                .build()
    }
}
