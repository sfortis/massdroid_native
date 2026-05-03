package net.asksakis.massdroidv2.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Generates initials-on-color placeholder bitmaps for AA browse rows that lack artwork.
 *
 * The Auto host renders MediaItem.artworkUri itself; passing a static drawable looks generic.
 * This provider mirrors the in-app InitialsBox composable (first letter of first two words on a
 * deterministic colored background) so library items without a real cover read consistently
 * across phone and car.
 *
 * URI shape: `content://<applicationId>.placeholder/initials?name=<urlencoded-name>`
 */
class PlaceholderArtworkProvider : ContentProvider() {

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
        val name = uri.getQueryParameter("name").orEmpty().ifBlank { "?" }
        val bytes = try {
            renderInitials(name)
        } catch (e: Exception) {
            Log.w(TAG, "render failed for '$name': ${e.message}")
            return null
        }
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "placeholder-pipe") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } catch (e: IOException) {
                Log.w(TAG, "pipe write failed: ${e.message}")
            }
        }
        return AssetFileDescriptor(pipe[0], 0, bytes.size.toLong())
    }

    private fun renderInitials(name: String): ByteArray {
        val initials = name.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "?" }

        val bg = PALETTE[(name.hashCode().rem(PALETTE.size).let { if (it < 0) it + PALETTE.size else it })]

        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bg)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (initials.length > 1) SIZE * 0.42f else SIZE * 0.5f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        paint.getTextBounds(initials, 0, initials.length, bounds)
        val xPos = SIZE / 2f
        val yPos = SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, xPos, yPos, paint)

        val out = ByteArrayOutputStream(BYTE_ESTIMATE)
        bitmap.compress(Bitmap.CompressFormat.PNG, COMPRESSION_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
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

    companion object {
        private const val TAG = "PlaceholderArt"
        private const val SIZE = 320
        private const val BYTE_ESTIMATE = 8 * 1024
        private const val COMPRESSION_QUALITY = 90

        // Palette tuned to read well on a dark AA background.
        private val PALETTE = intArrayOf(
            0xFF5E81AC.toInt(), // steel blue
            0xFFA3BE8C.toInt(), // sage green
            0xFFB48EAD.toInt(), // dusty purple
            0xFFD08770.toInt(), // burnt orange
            0xFFEBCB8B.toInt(), // muted gold
            0xFF88C0D0.toInt(), // pale teal
            0xFFBF616A.toInt(), // muted red
            0xFF8FBCBB.toInt(), // sea foam
        )

        fun uri(authority: String, name: String): Uri = Uri.parse("content://$authority/initials")
            .buildUpon()
            .appendQueryParameter("name", name)
            .build()
    }
}
