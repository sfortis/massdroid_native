package net.asksakis.massdroidv2.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    // The Share logs button sends this many of the most recent lines, as plain
    // text, so the attachment is small and pasteable (no 100 MB ZIP / ANR).
    private const val SHARE_TAIL_LINES = 1000

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
     * Build an [Intent] that shares the last [SHARE_TAIL_LINES] log lines as a
     * plain-text (.txt) attachment — small enough to email/paste and free of the
     * old whole-history ZIP that froze the UI thread. The text is gathered from
     * the current `app.log`, topped up from the previous rotated file when the
     * current one just rolled over, so the tail is always meaningful.
     *
     * Runs file I/O on [Dispatchers.IO]; returns null if there are no log files
     * yet (callers should surface a user-visible message).
     */
    suspend fun buildShareIntent(context: Context): Intent? = withContext(Dispatchers.IO) {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        // Newest first: app.log, then app.log.01, app.log.02, ...
        val files = logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("app.log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (files.isEmpty()) {
            Log.w(TAG, "No log files to share at ${logsDir.absolutePath}")
            return@withContext null
        }

        // Collect the last SHARE_TAIL_LINES lines, walking newest -> older.
        val tail = ArrayDeque<String>()
        for (file in files) {
            val fileLines = runCatching { file.readLines() }.getOrDefault(emptyList())
            var i = fileLines.lastIndex
            while (i >= 0 && tail.size < SHARE_TAIL_LINES) {
                tail.addFirst(fileLines[i])
                i--
            }
            if (tail.size >= SHARE_TAIL_LINES) break
        }
        if (tail.isEmpty()) {
            Log.w(TAG, "Log files present but empty; nothing to share")
            return@withContext null
        }

        val sharedDir = File(context.cacheDir, "shared_logs").apply { mkdirs() }
        // Drop previously shared snapshots so the cache does not accumulate.
        sharedDir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outFile = File(sharedDir, "massdroid-log-$stamp.txt")
        return@withContext try {
            outFile.writeText(tail.joinToString("\n"))
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
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
}
