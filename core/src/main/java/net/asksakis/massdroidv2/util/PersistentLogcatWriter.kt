package net.asksakis.massdroidv2.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Captures the device's logcat stream to rotating files inside the app's
 * external-files dir. logcat itself does the rotation via the `-f`, `-r`, and
 * `-n` options, so the overhead is a single forked process plus disk I/O.
 *
 * Files land at:
 *   /sdcard/Android/data/<package>/files/logs/app.log[.1..N]
 *
 * Total budget ≈ 10 MB × 10 = 100 MB, which is several hours of debug logs in
 * normal use and survives across logcat ring-buffer flushes. Pull them with:
 *   adb pull /sdcard/Android/data/<package>/files/logs/ ./logs/
 *
 * Lifecycle: started once from MassDroidApp.onCreate(). The forked process
 * dies with the app process (no separate stop path needed; if the app is
 * killed, the child terminates too).
 */
object PersistentLogcatWriter {
    private const val TAG = "PersistentLog"
    private const val MAX_KB_PER_FILE = 10_240
    private const val MAX_FILE_COUNT = 10

    @Volatile private var process: Process? = null

    @Synchronized
    fun start(context: Context) {
        if (process != null) return
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            Log.w(TAG, "Could not create logs dir at ${logsDir.absolutePath}")
            return
        }
        val target = File(logsDir, "app.log").absolutePath
        try {
            // -v threadtime: include thread id + level + tag, matches the
            //   default `adb logcat` formatting users are used to seeing.
            // -r / -n: rotate at MAX_KB_PER_FILE per file, keep MAX_FILE_COUNT
            //   files. logcat opens the next slot automatically when the
            //   current one passes the size threshold.
            // -f: write to file path (logcat handles the file rotation
            //   bookkeeping internally — no need for us to babysit it).
            process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat",
                    "-v", "threadtime",
                    "-f", target,
                    "-r", MAX_KB_PER_FILE.toString(),
                    "-n", MAX_FILE_COUNT.toString(),
                )
            )
            Log.d(TAG, "Persistent logcat writer started at $target ($MAX_KB_PER_FILE KB × $MAX_FILE_COUNT)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start persistent logcat writer: ${e.message}")
        }
    }

    /**
     * Bundle all rotated log files into a single ZIP in the cache dir and
     * return an [Intent] ready to launch via [Context.startActivity] with a
     * chooser. The ZIP lives at cache/shared_logs/ so it stays out of the
     * external-files dir we are concurrently writing to and is gated by
     * FileProvider URI permissions.
     *
     * Returns null if there are no log files yet (e.g. release build, or the
     * writer never started). Callers should handle null with a user-visible
     * message.
     */
    fun buildShareIntent(context: Context): Intent? {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        val files = logsDir.listFiles()?.filter { it.isFile && it.name.startsWith("app.log") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
        if (files.isEmpty()) {
            Log.w(TAG, "No log files to share at ${logsDir.absolutePath}")
            return null
        }

        val sharedDir = File(context.cacheDir, "shared_logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(sharedDir, "massdroid-logs-$stamp.zip")
        return try {
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MassDroid logs $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build share intent: ${e.message}")
            null
        }
    }
}
