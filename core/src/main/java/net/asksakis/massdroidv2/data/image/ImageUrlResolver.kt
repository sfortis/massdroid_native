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
    /**
     * Best image for an item: the direct image field, else the most-loadable metadata image.
     *
     * "Most-loadable" matters on MA 2.9+: a single item can carry both a proxied local image
     * (e.g. subsonic `cover_art`, remote=false -> proxy_id, loads anywhere) and a private-LAN
     * remotely-accessible URL (e.g. subsonic `artist_image_url`, no proxy_id, loads on-LAN only).
     * Naively picking the first thumb can land on the LAN-only one, so rank by [imageQuality]
     * first and keep the existing thumb-first preference only as a tie-break within a tier.
     */
    fun resolveItem(item: ServerMediaItem): String? {
        item.image?.let { resolve(it) }?.let { return it }
        val images = item.metadata?.images.orEmpty().filter { it.path.isNotBlank() }
        if (images.isEmpty()) return null
        val best = images.maxWithOrNull(
            compareBy<MediaItemImage> { imageQuality(it) }
                .thenBy { if (it.type.equals("thumb", ignoreCase = true)) 1 else 0 }
        )
        return best?.let { resolve(it) }
    }

    /** Item image, falling back to the album's image (used for tracks). */
    fun resolveItemWithAlbumFallback(item: ServerMediaItem): String? =
        resolveItem(item) ?: item.album?.let { resolveItem(it) }

    /**
     * Item image, album fallback, then a last-resort URI-based legacy proxy. The URI fallback only
     * ever helped pre-2.9: MA 2.9+ rejects the legacy `/imageproxy?path=<uri>` form for a non-http
     * scheme (e.g. `library://...`) with HTTP 400, so it is a guaranteed miss there - skip it (a
     * null result shows the same placeholder without a wasted request + server deprecation warning).
     */
    fun resolveItemWithUriFallback(item: ServerMediaItem): String? =
        resolveItem(item)
            ?: item.album?.let { resolveItem(it) }
            ?: item.uri.takeIf { isLegacyImageproxyServer() }?.let { fromPath(it) }

    /**
     * Resolve a single image to a loadable URL, covering both imageproxy routes across server
     * versions. Preference order: canonical proxy_id route -> direct URL -> legacy path proxy.
     *
     * Whatever [resolveOnServer] picks is rehosted before it is handed out, because an image path
     * can itself be a finished imageproxy URL carrying the server's internal address (MA stores
     * one that way for radio stations, and hands one out as the player's current media image).
     */
    fun resolve(image: MediaItemImage): String? = resolveOnServer(image)?.let { rehostImageproxyUrl(it) }

    private fun resolveOnServer(image: MediaItemImage): String? {
        val p = image.path.trim()
        if (p.isEmpty()) return null
        if (p.equals("none", ignoreCase = true) || p.equals("null", ignoreCase = true)) return null
        // A provider saying "I have no picture" by serving one anyway. Deezer
        // builds its image paths from a content hash and uses the hash of the
        // EMPTY string when there is nothing to hash, so this path is a
        // guaranteed generic avatar. Verified: it returns the same 9622 bytes as
        // the placeholder every other pictureless artist gets. Treated as no
        // image so the caller can fall back to something real.
        if (p.contains(EMPTY_CONTENT_HASH)) return null
        // (1) proxy_id: the canonical MA 2.9+ route on our own (external) server URL. The server
        // resolves the real path internally (SSRF-safe) and it is the ONLY way LAN/local provider
        // art (Jellyfin, filesystem, Plex, subsonic cover_art) loads on 2.9+. Best whenever present.
        image.proxyId?.let { id -> imageProxyIdUrl(id)?.let { return it } }
        // (2) remotely_accessible URL without a proxy_id (public CDN art, or a provider that serves
        // its own image URL such as subsonic artist_image_url).
        if (image.remotelyAccessible) {
            val host = runCatching { java.net.URI(p).host }.getOrNull()
            val privateHost = host != null && wsClient.isOffLanImageHost(host)
            // A private/LAN host is only reachable through the legacy proxy off-LAN, and MA 2.9+
            // rejects that path with HTTP 400 (the "missing images" reports). So proxy it only on
            // pre-2.9; on 2.9+ load the URL directly (what the MA web UI does - works on-LAN). A
            // public host is reachable anywhere, so always load it directly.
            return if (privateHost && isLegacyImageproxyServer()) {
                fromPath(p, provider = image.imageProvider) ?: p
            } else {
                p
            }
        }
        // (3) local/relative path (remote=false) without a proxy_id: only pre-2.9, or a 2.9 edge
        // where the serializer had no proxy_id resolver context. Legacy path proxy accepts these.
        return fromPath(p, provider = image.imageProvider) ?: p
    }

    /**
     * Loadability rank for [resolveItem] to prefer an image that actually renders:
     * proxy_id (3, loads anywhere) > local or public-remote (2, loads) > private-LAN remote with no
     * proxy_id on a 2.9+ server (1, on-LAN only). Higher is better.
     *
     * A private host is only a problem when the path points at the provider itself. One that is
     * already an imageproxy route names our own server under its internal address, and
     * [rehostImageproxyUrl] moves it onto the URL we connect through, so it loads anywhere.
     */
    private fun imageQuality(image: MediaItemImage): Int = when {
        image.proxyId != null -> PROXY_ID_QUALITY
        !image.remotelyAccessible -> LOADABLE_QUALITY
        else -> {
            val path = image.path.trim()
            val host = runCatching { java.net.URI(path).host }.getOrNull()
            val privateHost = host != null && wsClient.isOffLanImageHost(host)
            val reachable = !privateHost || isLegacyImageproxyServer() || isImageproxyUrl(path)
            if (reachable) LOADABLE_QUALITY else LAN_ONLY_QUALITY
        }
    }

    /**
     * True for a pre-2.9 server, where the legacy `/imageproxy?path=` route still resolves private
     * and non-http paths (so it is worth using off-LAN). MA 2.9+ (schema >= 31) rejects those with
     * HTTP 400, so the legacy route must not be used for them there.
     */
    private fun isLegacyImageproxyServer(): Boolean =
        (wsClient.serverSchemaVersion() ?: 0) < PROXY_ID_MIN_SCHEMA

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
     * Point an imageproxy URL at the server URL we connect through, whatever host the server
     * wrote into it. MA builds these with its own internal address deliberately: the player's
     * current media image comes from `mass.streams.base_url`, because that request is meant for
     * a player on the LAN, so off-LAN it names a host we cannot reach. The official web client
     * answers this the same way, rewriting every URL that contains an imageproxy segment onto
     * the base it connected on and never trusting the `base_url` the server announces.
     *
     * Handles both the legacy "/imageproxy?path=..." and the canonical MA 2.9 "/imageproxy/<id>".
     * A URL with no imageproxy segment, or a connection whose server URL is not known yet, is
     * returned unchanged.
     */
    fun rehostImageproxyUrl(url: String): String {
        if (!isImageproxyUrl(url)) return url
        val base = wsClient.externalServerUrl()?.trimEnd('/') ?: return url
        return base + url.substring(url.indexOf(IMAGEPROXY_SEGMENT))
    }

    /** True for a URL whose path is an imageproxy route, in either the id or the legacy form. */
    private fun isImageproxyUrl(url: String): Boolean {
        val idx = url.indexOf(IMAGEPROXY_SEGMENT)
        if (idx < 0) return false
        val after = url.getOrNull(idx + IMAGEPROXY_SEGMENT.length)
        return after == '/' || after == '?'
    }

    /** Canonical MA 2.9 route: {base}/imageproxy/<proxy_id>?size= on our external server URL. */
    private fun imageProxyIdUrl(proxyId: String, size: Int = DEFAULT_SIZE): String? {
        val base = wsClient.externalServerUrl()?.trimEnd('/') ?: return null
        return "$base/imageproxy/$proxyId?size=$size"
    }

    private companion object {
        const val DEFAULT_SIZE = 512
        /** md5(""), which a provider uses as the path of "no image here". */
        const val EMPTY_CONTENT_HASH = "d41d8cd98f00b204e9800998ecf8427e"
        const val IMAGEPROXY_SEGMENT = "/imageproxy"
        // MA API schema at/after which the proxy_id imageproxy route exists and the legacy
        // path route rejects private/non-http paths (the MA 2.9 line).
        const val PROXY_ID_MIN_SCHEMA = 31
        // imageQuality tiers (higher = more likely to render).
        const val PROXY_ID_QUALITY = 3
        const val LOADABLE_QUALITY = 2
        const val LAN_ONLY_QUALITY = 1
    }
}
