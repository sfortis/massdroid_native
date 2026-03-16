package net.asksakis.massdroidv2.data.provider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ProviderInstance
import net.asksakis.massdroidv2.data.websocket.ProviderManifest
import net.asksakis.massdroidv2.data.websocket.sendCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class MusicProvider(
    val instanceId: String,
    val domain: String,
    val name: String,
    val features: Set<String> = emptySet()
)

@Singleton
class ProviderManifestCache @Inject constructor() {

    private val manifests = ConcurrentHashMap<String, ProviderManifest>()
    private val iconCacheLight = ConcurrentHashMap<String, ImageBitmap>()
    private val iconCacheDark = ConcurrentHashMap<String, ImageBitmap>()

    private val _musicProviders = MutableStateFlow<List<MusicProvider>>(emptyList())
    val musicProviders: List<MusicProvider> get() = _musicProviders.value
    val musicProvidersFlow: StateFlow<List<MusicProvider>> = _musicProviders.asStateFlow()

    suspend fun fetchManifests(wsClient: MaWebSocketClient, json: Json) {
        try {
            val result = wsClient.sendCommand(MaCommands.Providers.MANIFESTS) ?: return
            val list = json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ProviderManifest.serializer()),
                result
            )
            manifests.clear()
            iconCacheLight.clear()
            iconCacheDark.clear()
            for (manifest in list) {
                manifests[manifest.domain] = manifest
                renderIcon(manifest, dark = false)?.let { iconCacheLight[manifest.domain] = it }
                renderIcon(manifest, dark = true)?.let { iconCacheDark[manifest.domain] = it }
            }
            Log.d(TAG, "Loaded ${manifests.size} provider manifests, " +
                "${iconCacheLight.size} light icons, ${iconCacheDark.size} dark icons")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch provider manifests: ${e.message}")
        }
        try {
            val result = wsClient.sendCommand(MaCommands.Providers.LIST) ?: return
            val instances = json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ProviderInstance.serializer()),
                result
            )
            _musicProviders.value = instances
                .filter { it.type == "music" && it.domain != "builtin" }
                .map { MusicProvider(it.instanceId, it.domain, it.name, it.supportedFeatures.toSet()) }
            Log.d(TAG, "Music providers: ${musicProviders.map { it.name }}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch provider instances: ${e.message}")
        }
    }

    fun musicProvidersForTab(tab: Int): List<MusicProvider> {
        val requiredFeature = when (tab) {
            0 -> "library_artists"
            1 -> "library_albums"
            2 -> "library_tracks"
            3 -> "library_playlists"
            4 -> "library_radios"
            else -> return emptyList()
        }
        return musicProviders.filter { requiredFeature in it.features }
    }

    fun getIcon(domain: String, dark: Boolean): ImageBitmap? {
        val cache = if (dark) iconCacheDark else iconCacheLight
        return cache[domain] ?: resolveAlias(domain)?.let { cache[it] }
    }

    fun getName(domain: String): String? =
        manifests[domain]?.name ?: resolveAlias(domain)?.let { manifests[it]?.name }

    fun getFallbackIconName(domain: String): String? =
        manifests[domain]?.icon ?: resolveAlias(domain)?.let { manifests[it]?.icon }

    private fun resolveAlias(domain: String): String? =
        if ("--" in domain) domain.substringBefore("--") else null

    private fun renderIcon(manifest: ProviderManifest, dark: Boolean): ImageBitmap? {
        val svgString = (if (dark) manifest.iconSvgDark ?: manifest.iconSvg else manifest.iconSvg)
            ?: return null
        return try {
            val svg = SVG.getFromString(svgString)
            svg.documentWidth = ICON_SIZE.toFloat()
            svg.documentHeight = ICON_SIZE.toFloat()
            val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(canvas)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to render SVG for ${manifest.domain}: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ProviderCache"
        private const val ICON_SIZE = 48
    }
}
