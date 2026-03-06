package net.asksakis.massdroidv2.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    baseOkHttpClient: OkHttpClient
) {

    companion object {
        private const val PREFS_NAME = "app_update_prefs"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val CHECK_INTERVAL_MS = 8 * 60 * 60 * 1000L
        private const val GITHUB_RELEASES_URL =
            "https://api.github.com/repos/sfortis/massdroid_native/releases"
    }

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val publishedAt: String,
        val fileSizeBytes: Long
    )

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        data object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    suspend fun checkForUpdates(
        force: Boolean = false,
        includePrerelease: Boolean = false
    ): CheckResult = withContext(Dispatchers.IO) {
        if (!force && !shouldCheck()) return@withContext CheckResult.UpToDate

        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        runCatching {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext CheckResult.Error("GitHub returned ${response.code}")
                }

                val body = response.body?.string().orEmpty()
                val releases = org.json.JSONArray(body)
                var releaseJson: JSONObject? = null
                for (i in 0 until releases.length()) {
                    val candidate = releases.getJSONObject(i)
                    val isDraft = candidate.optBoolean("draft", false)
                    val isPrerelease = candidate.optBoolean("prerelease", false)
                    if (isDraft) continue
                    if (!includePrerelease && isPrerelease) continue
                    releaseJson = candidate
                    break
                }

                if (releaseJson == null) {
                    return@withContext CheckResult.Error("No matching GitHub release found")
                }

                val latestVersion = releaseJson.optString("tag_name").removePrefix("v")
                val currentVersion = getCurrentVersion()

                if (!isNewerVersion(currentVersion, latestVersion)) {
                    return@withContext CheckResult.UpToDate
                }

                val assets = releaseJson.optJSONArray("assets")
                var apkAsset: JSONObject? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkAsset = asset
                            break
                        }
                    }
                }

                if (apkAsset == null) {
                    return@withContext CheckResult.Error("No APK asset found in latest release")
                }

                CheckResult.UpdateAvailable(
                    UpdateInfo(
                        version = latestVersion,
                        downloadUrl = apkAsset.getString("browser_download_url"),
                        releaseNotes = releaseJson.optString("body").ifBlank { "No release notes." },
                        publishedAt = releaseJson.optString("published_at"),
                        fileSizeBytes = apkAsset.optLong("size")
                    )
                )
            }
        }.getOrElse { error ->
            CheckResult.Error(error.message ?: "Failed to check for updates")
        }
    }

    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: suspend (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed with ${response.code}")
                }

                val body = response.body ?: error("Empty download body")
                val outputFile = File(context.cacheDir, "massdroid-update-${updateInfo.version}.apk")
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (contentLength > 0) {
                                onProgress(((downloaded * 100) / contentLength).toInt().coerceIn(0, 100))
                            }
                        }
                        output.flush()
                    }
                }

                outputFile
            }
        }
    }

    fun buildInstallIntent(apkFile: File): Intent {
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun shouldCheck(): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val max = maxOf(currentParts.size, latestParts.size)

        repeat(max) { index ->
            val currentPart = currentParts.getOrElse(index) { 0 }
            val latestPart = latestParts.getOrElse(index) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }
}
