package net.asksakis.massdroidv2.data.image

import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.MediaItemImage
import net.asksakis.massdroidv2.data.websocket.ServerMediaItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single home for turning MA media items / images into loadable image URLs.
 *
 * Owns the whole MA imageproxy policy: the MA 2.9 canonical `proxy_id` route, the legacy
 * path-based form (pre-2.9 fallback), the off-LAN handling for "remotely accessible" art, and
 * the host rewrite for server-pre-built `image_url` strings. Keeping it in one class means a
 * server-side change to image handling is a one-file edit, instead of leaking into the WS
 * client, the data models and every call site (which is what made the MA 2.9 fix sprawl).
 *
 * Reads the live external server URL and the off-LAN check from [MaWebSocketClient] (both are
 * connection-context that belongs with the client); everything URL-shaped lives here.
 */
@Singleton
class ImageUrlResolver @Inject constructor(
    private val wsClient: MaWebSocketClient,
) {
    /** Best image for an item: the direct image field, else the first (thumb) metadata image. */
    fun resolveItem(item: ServerMediaItem): String? {
        item.image?.let { resolve(it) }?.let { return it }
        val images = item.metadata?.images ?: return null
        val thumb = images.firstOrNull { it.type.equals("thumb", ignoreCase = true) }
            ?: images.firstOrNull()
            ?: return null
        return resolve(thumb)
    }

    /** Item image, falling back to the album's image (used for tracks). */
    fun resolveItemWithAlbumFallback(item: ServerMediaItem): String? =
        resolveItem(item) ?: item.album?.let { resolveItem(it) }

    /** Item image, album fallback, then a last-resort URI-based legacy proxy. */
    fun resolveItemWithUriFallback(item: ServerMediaItem): String? =
        resolveItem(item)
            ?: item.album?.let { resolveItem(it) }
            ?: fromPath(item.uri)

    /** Resolve a single image to a loadable URL. */
    fun resolve(image: MediaItemImage): String? {
        val p = image.path.trim()
        if (p.isEmpty()) return null
        if (p.equals("none", ignoreCase = true) || p.equals("null", ignoreCase = true)) return null
        // MA 2.9+ hands every non-public image an opaque proxy_id. Fetch it via the canonical
        // /imageproxy/<proxy_id> route on our own (external) server URL: the server resolves the
        // real path internally, so it is SSRF-safe and is the ONLY way LAN/local provider art
        // (Jellyfin, filesystem, Plex, subsonic) loads. The legacy path-based /imageproxy now
        // rejects private/LAN URLs with HTTP 400 (the cause of the "missing images" reports).
        image.proxyId?.let { id -> imageProxyIdUrl(id)?.let { return it } }
        if (image.remotelyAccessible) {
            // Public URL: use directly. On a pre-2.9 server a LAN-only "remotely accessible" host
            // still needs the legacy proxy off-LAN (cellular / remote / VPN endpoint).
            val host = runCatching { java.net.URI(p).host }.getOrNull()
            if (host != null && wsClient.isOffLanImageHost(host)) {
                return fromPath(p, provider = image.imageProvider) ?: p
            }
            return p
        }
        // Pre-2.9 server (no proxy_id): legacy path-based proxy.
        return fromPath(p, provider = image.imageProvider) ?: p
    }

    /**
     * Legacy path-based imageproxy URL ({base}/imageproxy?path=&size=&provider=). Kept for pre-2.9
     * servers and the URI last-resort; MA 2.9 rejects private/LAN paths here (use [resolve]'s
     * proxy_id route for those). Built on the user-configured external server URL, not the
     * internal base_url. Size comes from MA's whitelist {0, 80, 160, 256, 512, 1024}.
     */
    fun fromPath(imagePath: String, size: Int = DEFAULT_SIZE, provider: String? = null): String? {
        val base = wsClient.externalServerUrl()?.trimEnd('/') ?: return null
        val encodedPath = java.net.URLEncoder.encode(imagePath, "UTF-8")
        val providerParam = if (!provider.isNullOrEmpty()) "&provider=$provider" else ""
        return "$base/imageproxy?path=$encodedPath&size=$size$providerParam"
    }

    /**
     * Rehost a server-pre-built image_url (e.g. player current_media.image_url) from the server's
     * INTERNAL base_url host to the user-configured external server URL, so it loads off-LAN.
     * Handles both the legacy "/imageproxy?path=..." and the canonical MA 2.9 "/imageproxy/<id>".
     */
    fun rewritePrebuilt(url: String): String {
        val idx = url.indexOf("/imageproxy")
        if (idx < 0) return url
        val after = url.getOrNull(idx + IMAGEPROXY_SEGMENT.length)
        if (after != '/' && after != '?') return url
        val base = wsClient.externalServerUrl()?.trimEnd('/') ?: return url
        return base + url.substring(idx)
    }

    /** Canonical MA 2.9 route: {base}/imageproxy/<proxy_id>?size= on our external server URL. */
    private fun imageProxyIdUrl(proxyId: String, size: Int = DEFAULT_SIZE): String? {
        val base = wsClient.externalServerUrl()?.trimEnd('/') ?: return null
        return "$base/imageproxy/$proxyId?size=$size"
    }

    private companion object {
        const val DEFAULT_SIZE = 512
        const val IMAGEPROXY_SEGMENT = "/imageproxy"
    }
}
