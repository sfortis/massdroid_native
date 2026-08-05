package net.asksakis.massdroidv2.data.image

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.MediaItemImage
import net.asksakis.massdroidv2.data.websocket.MediaItemMetadata
import net.asksakis.massdroidv2.data.websocket.ServerMediaItem
import org.junit.Test

/**
 * Covers the whole MA imageproxy resolution matrix across server versions and providers, the
 * #54 root cause: MA 2.9+ (schema >= 31) only fills `proxy_id` for `remotely_accessible=false`
 * images and rejects the legacy `/imageproxy?path=` route for private/LAN and non-http paths.
 */
class ImageUrlResolverTest {

    private val serverBase = "https://mass.asksakis.net"
    private val publicHost = "assets.fanart.tv"
    private val lanHost = "192.168.1.50"

    /** [schema] 31 = MA 2.9+, 30 = pre-2.9. Private-host detection mirrors the real client. */
    private fun resolver(schema: Int): ImageUrlResolver {
        val ws = mockk<MaWebSocketClient>()
        every { ws.externalServerUrl() } returns serverBase
        every { ws.serverSchemaVersion() } returns schema
        every { ws.isOffLanImageHost(any()) } answers {
            val h = firstArg<String>()
            // private/LAN host that is not the server host = off-LAN (needs proxy on pre-2.9)
            h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("127.")
        }
        return ImageUrlResolver(ws)
    }

    private fun img(
        path: String,
        remote: Boolean = false,
        proxyId: String? = null,
        provider: String = "builtin",
        type: String = "thumb",
    ) = MediaItemImage(type = type, path = path, imageProvider = provider, remotelyAccessible = remote, proxyId = proxyId)

    private fun item(vararg images: MediaItemImage, uri: String = "library://artist/1") =
        ServerMediaItem(itemId = "1", uri = uri, metadata = MediaItemMetadata(images = images.toList()))

    // --- proxy_id: canonical route, any server version ---

    @Test
    fun `proxy_id uses the canonical imageproxy id route`() {
        val url = resolver(31).resolve(img("Eels/Folder.jpg", remote = false, proxyId = "abc123"))
        assertThat(url).isEqualTo("$serverBase/imageproxy/abc123?size=512")
    }

    @Test
    fun `proxy_id wins even for a remotely accessible image`() {
        val url = resolver(31).resolve(img("http://$lanHost/art.jpg", remote = true, proxyId = "xyz"))
        assertThat(url).isEqualTo("$serverBase/imageproxy/xyz?size=512")
    }

    // --- remotely accessible, no proxy_id ---

    @Test
    fun `remote public host loads directly on 2_9`() {
        val path = "https://$publicHost/air.jpg"
        assertThat(resolver(31).resolve(img(path, remote = true))).isEqualTo(path)
    }

    @Test
    fun `remote private LAN host loads directly on 2_9 (no dead legacy wrap)`() {
        val path = "http://$lanHost:4040/rest/getCoverArt?id=ar-1"
        // The #54 fix: on 2.9+ the legacy proxy would 400, so load direct (web-UI parity).
        assertThat(resolver(31).resolve(img(path, remote = true))).isEqualTo(path)
    }

    @Test
    fun `remote private LAN host still proxied on pre-2_9`() {
        val path = "http://$lanHost:4040/rest/getCoverArt?id=ar-1"
        val url = resolver(30).resolve(img(path, remote = true, provider = "opensubsonic"))
        assertThat(url).startsWith("$serverBase/imageproxy?path=")
        assertThat(url).contains("provider=opensubsonic")
    }

    // --- local path, no proxy_id (pre-2.9 / edge) ---

    @Test
    fun `local path without proxy_id uses legacy path proxy`() {
        val url = resolver(30).resolve(img("Eels/Folder.jpg", remote = false, provider = "filesystem"))
        assertThat(url).startsWith("$serverBase/imageproxy?path=")
        assertThat(url).contains("provider=filesystem")
    }

    // --- empty / sentinel paths ---

    @Test
    fun `blank and sentinel paths resolve to null`() {
        val r = resolver(31)
        assertThat(r.resolve(img(""))).isNull()
        assertThat(r.resolve(img("none"))).isNull()
        assertThat(r.resolve(img("null"))).isNull()
    }

    // --- resolveItem: prefer the most-loadable image ---

    @Test
    fun `resolveItem prefers a proxy_id image over a private LAN remote one on 2_9`() {
        // subsonic artist with both cover_art (remote=false -> proxy_id) and artist_image_url (LAN)
        val coverArt = img("cover-art-id", remote = false, proxyId = "cover99")
        val lanImage = img("http://$lanHost/artist.jpg", remote = true)
        // LAN image listed first (parser order can vary) - must still pick the proxied one.
        val url = resolver(31).resolveItem(item(lanImage, coverArt))
        assertThat(url).isEqualTo("$serverBase/imageproxy/cover99?size=512")
    }

    @Test
    fun `resolveItem falls back to the LAN image when it is the only one`() {
        val path = "http://$lanHost/artist.jpg"
        val url = resolver(31).resolveItem(item(img(path, remote = true)))
        assertThat(url).isEqualTo(path)
    }

    // --- URI last-resort gating ---

    @Test
    fun `uri fallback is skipped on 2_9 (guaranteed 400)`() {
        val imageless = ServerMediaItem(itemId = "1", uri = "library://artist/162")
        assertThat(resolver(31).resolveItemWithUriFallback(imageless)).isNull()
    }

    @Test
    fun `uri fallback still used on pre-2_9`() {
        val imageless = ServerMediaItem(itemId = "1", uri = "library://artist/162")
        val url = resolver(30).resolveItemWithUriFallback(imageless)
        assertThat(url).startsWith("$serverBase/imageproxy?path=")
    }

    @Test
    fun `a provider's no-image placeholder counts as no image`() {
        // Deezer names its image paths after a content hash and uses the hash of
        // the EMPTY string when the artist has no picture. Verified against the
        // live CDN: that path and a pictureless artist's own path return the
        // same 9622 bytes, so showing it is showing a grey avatar. Returning
        // null lets the caller fall back to an album cover instead.
        val placeholder = img(
            "https://cdn-images.dzcdn.net/images/artist/d41d8cd98f00b204e9800998ecf8427e/500x500-000000-80-0-0.jpg",
            remote = true,
            proxyId = "abc123",
        )
        assertThat(resolver(schema = 31).resolve(placeholder)).isNull()
    }

    @Test
    fun `a real provider image is still resolved`() {
        val real = img(
            "https://cdn-images.dzcdn.net/images/artist/c6b695f353571aed893421a0f135b499/500x500.jpg",
            remote = true,
            proxyId = "abc123",
        )
        assertThat(resolver(schema = 31).resolve(real)).isNotNull()
    }
}
