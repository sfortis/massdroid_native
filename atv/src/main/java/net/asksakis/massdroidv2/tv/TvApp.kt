package net.asksakis.massdroidv2.tv

import android.app.Application
import android.security.KeyChain
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Android TV (Shield) application entry point. Shares all non-UI logic with the
 * phone app via :core (data, domain, Sendspin engine + coordination, native).
 * The phone-specific bootstrap (PlaybackService, Android Auto, Follow Me) is
 * intentionally NOT here; the TV adds its own foreground Sendspin player service.
 */
@HiltAndroidApp
class TvApp : Application(), ImageLoaderFactory {

    @Inject lateinit var wsClient: MaWebSocketClient
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Same connection prerequisites as the phone app: load the saved mTLS
        // client cert and credentials, then mark the WS client ready so the UI
        // can auto-connect. Reuses the identical :core MaWebSocketClient.
        appScope.launch {
            val alias = settingsRepository.clientCertAlias.first()
            if (alias != null) {
                try {
                    val privateKey = KeyChain.getPrivateKey(this@TvApp, alias)
                    val certChain = KeyChain.getCertificateChain(this@TvApp, alias)
                    if (privateKey != null && certChain != null) {
                        wsClient.configureMtls(privateKey, certChain)
                        Log.d(TAG, "mTLS loaded on startup: $alias")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load mTLS cert: ${e.message}")
                }
            }
            val username = settingsRepository.username.first()
            val password = settingsRepository.password.first()
            if (username.isNotBlank() && password.isNotBlank()) {
                wsClient.setSavedCredentials(username, password)
            }
            wsClient.markStartupReady()
        }
    }

    /** Load artwork through the same authenticated/mTLS-aware OkHttp client as
     *  the WS connection, mirroring the phone app. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { wsClient.getHttpClient() }
            .crossfade(true)
            .build()

    private companion object {
        const val TAG = "TvApp"
    }
}
