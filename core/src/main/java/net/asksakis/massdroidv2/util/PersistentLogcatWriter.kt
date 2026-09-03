package net.asksakis.massdroidv2.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Retention is one day: [cleanupOldLogs] (run on start) deletes any log file
 * older than [MAX_AGE_MS], and the size rotation ([MAX_KB_PER_FILE] x
 * [MAX_FILE_COUNT]) is a disk backstop so a high-volume day cannot run away.
 * Pull them manually with:
 *   adb pull /sdcard/Android/data/<package>/files/logs/ ./logs/
 *
 * Lifecycle: started once from MassDroidApp.onCreate(). The forked process
 * dies with the app process (no separate stop path needed; if the app is
 * killed, the child terminates too).
 */
object PersistentLogcatWriter {
    private const val TAG = "PersistentLog"
    private const val MAX_KB_PER_FILE = 5_120
    private const val MAX_FILE_COUNT = 6
    // Retention ceiling: drop any log file not touched within the last day.
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    // Name of the summary entry placed first in the shared archive.
    private const val SUMMARY_ENTRY = "device.txt"

    @Volatile private var process: Process? = null

    @Synchronized
    fun start(context: Context) {
        if (process != null) return
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            Log.w(TAG, "Could not create logs dir at ${logsDir.absolutePath}")
            return
        }
        // Enforce the one-day retention BEFORE logcat reopens the files, so we
        // never delete a file logcat is actively rotating.
        cleanupOldLogs(logsDir)
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

    /** Delete log files whose last write is older than the one-day retention. */
    private fun cleanupOldLogs(logsDir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("app.log") && it.lastModified() < cutoff }
            ?.forEach { stale ->
                if (stale.delete()) Log.d(TAG, "Dropped stale log ${stale.name} (>1 day)")
            }
    }

    /**
     * Build an [Intent] that shares the whole retained log history as one ZIP,
     * with a [SUMMARY_ENTRY] listing app, device and server versions first.
     *
     * This deliberately sends everything rather than a tail. A tail was measured
     * against the real thing: while music plays the log runs at about 440 lines a
     * minute, so the previous 1000-line window covered roughly two and a half
     * minutes. Nobody reports a fault that fast. The two audio-focus reports that
     * prompted this were filed three minutes apart for events that happened hours
     * earlier and on different occasions, and the Android Auto one can only be
     * filed after parking. Retention caps the archive: one day, and at most
     * [MAX_KB_PER_FILE] x [MAX_FILE_COUNT] of text, which zips down to a few MB.
     *
     * A ZIP was removed once before (f4b0200) because it was built on the calling
     * UI thread and ANRed on a whole history. The format was never the problem;
     * the threading was. This runs inside [withContext] on [Dispatchers.IO], so
     * the main thread never touches the files.
     *
     * Returns null if there are no log files yet (callers should surface a
     * user-visible message: on Android 11+ `logcat` often yields nothing without
     * READ_LOGS, and then there is genuinely nothing to send).
     */
    suspend fun buildShareIntent(
        context: Context,
        serverVersion: String? = null,
    ): Intent? = withContext(Dispatchers.IO) {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        // Oldest first, so the archive reads in chronological order.
        val files = logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("app.log") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
        if (files.isEmpty()) {
            Log.w(TAG, "No log files to share at ${logsDir.absolutePath}")
            return@withContext null
        }

        val sharedDir = File(context.cacheDir, "shared_logs").apply { mkdirs() }
        // Drop previously shared snapshots so the cache does not accumulate.
        sharedDir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(sharedDir, "massdroid-logs-$stamp.zip")
        return@withContext try {
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(SUMMARY_ENTRY))
                zip.write(buildSummary(context, serverVersion, stamp).toByteArray())
                zip.closeEntry()
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name))
                    // The current app.log is being appended to by the logcat
                    // process; copying it mid-write just yields a short tail.
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile,
            )
            Log.i(TAG, "Sharing ${files.size} log files as ${zipFile.name} (${zipFile.length() / 1024} KB)")
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MassDroid logs $stamp")
                // ClipData makes the read grant stick on targets that read the
                // attachment from the clip (e.g. Gmail) rather than EXTRA_STREAM.
                clipData = ClipData.newRawUri("MassDroid logs", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build share intent: ${e.message}")
            null
        }
    }

    /**
     * The versions a bug report otherwise costs a round trip to establish: which
     * build, which phone, which Android, which Music Assistant server.
     */
    private fun buildSummary(context: Context, serverVersion: String?, stamp: String): String {
        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return buildString {
            appendLine("MassDroid log bundle")
            appendLine("captured: $stamp")
            appendLine("app: $appVersion (${context.packageName})")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("ma server: ${serverVersion ?: "not connected"}")
        }
    }
}
