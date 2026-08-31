package net.asksakis.massdroidv2

import android.app.Application
import android.security.KeyChain
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.update.AppUpdateChecker
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import net.asksakis.massdroidv2.BuildConfig

@HiltAndroidApp
class MassDroidApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var wsClient: MaWebSocketClient

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var playHistoryRepository: PlayHistoryRepository

    @Inject
    lateinit var appUpdateChecker: AppUpdateChecker

    @Inject
    lateinit var providerManifestCache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache

    @Inject
    lateinit var smartListeningRepository: net.asksakis.massdroidv2.domain.repository.SmartListeningRepository

    @Inject
    lateinit var json: kotlinx.serialization.json.Json

    @Inject
    lateinit var libraryGenreEnricher: net.asksakis.massdroidv2.data.genre.LibraryGenreEnricher

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Deferred library genre enrichment. Held so we can cancel a previously
     * scheduled sync if the connection drops/reconnects before the delay
     * elapses, instead of stacking parallel launches across flaps.
     */
    private var genreEnrichmentJob: Job? = null

    /**
     * How long we wait after WS Connected before paginating the entire
     * library through music/artists/library_items. 5 s is long enough to
     * clear the cold-start RPC burst (auth, providers, players, queue,
     * blocked-queue cleanup) without making the user wait noticeably
     * longer for genre data in Discover / Library.
     */
    private val genreEnrichmentStartupDelayMs = 5_000L

    override fun onCreate() {
        super.onCreate()
        // Start the persistent logcat-to-file writer first so we capture
        // everything that follows. We try in release too because the user
        // needs the Share logs button to work when reporting field issues;
        // on Android 11+ Runtime.exec("logcat") often returns nothing
        // without READ_LOGS, but the writer fails gracefully and the share
        // button surfaces a "no logs available" message in that case.
        net.asksakis.massdroidv2.util.PersistentLogcatWriter.start(this)
        // Load saved mTLS certificate and credentials at startup
        appScope.launch {
            val alias = settingsRepository.clientCertAlias.first()
            if (alias != null) {
                try {
                    val privateKey = KeyChain.getPrivateKey(this@MassDroidApp, alias)
                    val certChain = KeyChain.getCertificateChain(this@MassDroidApp, alias)
                    if (privateKey != null && certChain != null) {
                        wsClient.configureMtls(privateKey, certChain)
                        Log.d("MassDroidApp", "mTLS loaded on startup: $alias")
                    }
                } catch (e: Exception) {
                    Log.e("MassDroidApp", "Failed to load mTLS cert: ${e.message}")
                }
            }
            // Load saved credentials for token-fallback
            val username = settingsRepository.username.first()
            val password = settingsRepository.password.first()
            if (username.isNotBlank() && password.isNotBlank()) {
                wsClient.setSavedCredentials(username, password)
                Log.d("MassDroidApp", "Saved credentials loaded for user: $username")
            }
            wsClient.markStartupReady()
        }

        // Clean up old play history entries
        appScope.launch {
            try {
                playHistoryRepository.cleanup(retentionMonths = 6)
            } catch (e: Exception) {
                Log.e("MassDroidApp", "Play history cleanup failed: ${e.message}")
            }
        }

        // Self-update via GitHub Releases is the github flavor only; the fdroid
        // flavor (ENABLE_UPDATE_CHECK=false) updates through F-Droid's repository.
        if (BuildConfig.ENABLE_UPDATE_CHECK) {
            appScope.launch {
                try {
                    val includeBeta = settingsRepository.includeBetaUpdates.first()
                    val result = appUpdateChecker.checkForUpdates(force = false, includePrerelease = includeBeta)
                    if (result is net.asksakis.massdroidv2.data.update.AppUpdateChecker.CheckResult.UpdateAvailable) {
                        Log.d("MassDroidApp", "Update available: ${result.info.version}")
                    }
                } catch (e: Exception) {
                    Log.d("MassDroidApp", "Background update check skipped: ${e.message}")
                }
            }
        }

        // Connect to PlaybackService for media notification (required for MIUI/vendor ROMs
        // that block late service binding)
        connectPlaybackService()

        // Observe connection state: save token on connect, clear on auth failure
        appScope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        // Save fresh token to DataStore before anything reads it
                        wsClient.authToken?.let { token ->
                            settingsRepository.setAuthToken(token)
                            Log.d("MassDroidApp", "Token saved to DataStore")
                        }
                        providerManifestCache.fetchManifests(wsClient, json)
                        // Blocked artists are stored under every uri the server
                        // knows for them, which is a snapshot of the providers
                        // configured at the time. Re-expanding whenever that set
                        // changes is what keeps a blocked artist blocked after a
                        // new provider gives them a uri nobody blocked. Runs here
                        // because the fingerprint is only known once the provider
                        // list above has been read.
                        appScope.launch {
                            val fingerprint = providerManifestCache.musicProviders
                                .map { it.instanceId }
                                .sorted()
                                .joinToString(",")
                            runCatching {
                                smartListeningRepository.backfillBlockedArtistAliases(fingerprint)
                            }.onFailure {
                                Log.w("MassDroidApp", "Blocked-artist expansion failed: ${it.message}")
                            }
                        }
                        // Defer the library enrichment past the cold-start RPC burst
                        // (auth, providers, players, queue refresh, blocked-queue
                        // cleanup) so we don't pile a paginated music/artists/library_items
                        // sweep on top of the server while it is still answering the
                        // critical-path queries. Cancel any previously scheduled sync
                        // so flap reconnects don't stack parallel launches.
                        genreEnrichmentJob?.cancel()
                        genreEnrichmentJob = appScope.launch {
                            delay(genreEnrichmentStartupDelayMs)
                            libraryGenreEnricher.enrichAllUnenriched()
                        }
                    }
                    is ConnectionState.Error -> {
                        // If token was rejected (cleared by WS client), clear from DataStore too
                        if (wsClient.authToken == null) {
                            settingsRepository.setAuthToken("")
                            Log.d("MassDroidApp", "Invalid token cleared from DataStore")
                        }
                        genreEnrichmentJob?.cancel()
                        genreEnrichmentJob = null
                    }
                    is ConnectionState.Disconnected -> {
                        genreEnrichmentJob?.cancel()
                        genreEnrichmentJob = null
                    }
                    else -> {}
                }
            }
        }
    }

    private fun connectPlaybackService() {
        appScope.launch(Dispatchers.Main) {
            try {
                val sessionToken = androidx.media3.session.SessionToken(
                    this@MassDroidApp,
                    android.content.ComponentName(this@MassDroidApp, net.asksakis.massdroidv2.service.PlaybackService::class.java)
                )
                androidx.media3.session.MediaController.Builder(this@MassDroidApp, sessionToken)
                    .buildAsync()
                Log.d("MassDroidApp", "PlaybackService controller connected")
            } catch (e: Exception) {
                Log.e("MassDroidApp", "PlaybackService connect failed: ${e.message}")
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // The IMAGE client (connect 10s / read 15s), never the WebSocket one.
            // Both share the mTLS config and connection pool, but the WS client sets
            // readTimeout(0) - correct for a socket meant to stay open for hours,
            // fatal for a one-shot image fetch: a half-open socket after a WiFi to
            // mobile handover blocks the caller forever with no timeout to free it.
            // On AAOS that caller is a binder thread, and 16 of them frozen means
            // MediaSession stops answering play/pause/skip entirely (issue #37).
            .okHttpClient { wsClient.getImageClient() }
            .components { add(coil.decode.SvgDecoder.Factory()) }
            // Wired in every build, because a "missing images" report is unanswerable
            // without it: the listener names the exact URL that failed and why (imageproxy
            // 400/404, unreachable LAN host). It was debug-only, which meant the one tool
            // for the job was absent from every build a user actually runs, and issue #66
            // sat undiagnosed for a week. Successes stay debug-only, see below.
            .eventListener(ImageDebugEventListener)
            .crossfade(true)
            // Artwork sources differ wildly in HTTP cache headers: fanart.tv sends NONE (no
            // Cache-Control/Last-Modified/ETag), which the default header-respecting policy
            // treats as always-stale, so those images re-download on EVERY display. Artwork is
            // content-addressed (URL changes when the image changes), so ignore HTTP caching
            // semantics and serve everything from the disk cache unconditionally.
            .respectCacheHeaders(false)
            // Coil's default disk cache caps at 250MB; a full artwork library at size=512 is
            // ~250MB+, so the LRU evicts constantly and covers re-download on every launch.
            // Same directory as the default so existing entries survive the upgrade.
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }

    private companion object {
        const val IMAGE_DISK_CACHE_BYTES = 1024L * 1024 * 1024 // 1 GB
    }
}

/**
 * Image-load tracer. Logs the exact URL and outcome under tag `ImageDbg` at a level that survives
 * release stripping (i/w) and is captured by the persistent log writer, so a "missing images"
 * report can be diagnosed from shared logs: the failing URL and the reason.
 *
 * FAILURES are logged in every build. They are rare in a working setup, so the cost is nothing,
 * and they are the whole of what a bug report needs.
 *
 * SUCCESSES stay on debug builds. A library browse loads hundreds of images, and writing a line
 * for each into a 6x5MB rolling log would push the evidence of the actual fault out of it.
 */
private object ImageDebugEventListener : coil.EventListener {
    private const val TAG = "ImageDbg"
    private const val MAX_DISTINCT_FAILURES = 200

    override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
        if (!BuildConfig.DEBUG) return
        // Only network loads matter for diagnosis; cache hits just echo an earlier network fetch.
        if (result.dataSource == coil.decode.DataSource.NETWORK) {
            Log.i(TAG, "OK (net): ${request.data}")
        }
    }

    override fun onError(request: coil.request.ImageRequest, result: coil.request.ErrorResult) {
        // The query string is dropped, not shortened. A Subsonic image URL carries the
        // account and password (or token and salt) in it, and this line goes into the
        // rolling log that the About screen invites the user to share. The host and path
        // are what identify the fault; the credentials are not needed to read it.
        val where = redactQuery(request.data.toString())
        if (!failuresSeen.add(where)) return
        Log.w(TAG, "FAIL: $where -> ${result.throwable.javaClass.simpleName}: ${result.throwable.message}")
    }

    /**
     * Distinct failures already logged this process, so one unreachable host cannot fill
     * the log with a line per image and push the rest of the evidence out of it. Bounded,
     * because a library browse can attempt thousands.
     */
    private val failuresSeen = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= MAX_DISTINCT_FAILURES) return false
            return super.add(element)
        }
    }

    private fun redactQuery(url: String): String {
        val cut = url.indexOf('?')
        return if (cut < 0) url else url.substring(0, cut)
    }
}
