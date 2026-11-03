package net.asksakis.massdroidv2.domain.connection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The sign-in form reads a server address and a port. A Home Assistant user typically types
 * "homeassistant.local" or the Home Assistant address, so both must lead somewhere useful.
 */
class ServerAddressTest {

    private fun valid(address: String, port: String = "") =
        parseServerAddress(address, port) as ServerAddress.Valid

    @Test
    fun `a bare host gets http and the Music Assistant port`() {
        assertThat(valid("homeassistant.local", "8095").url).isEqualTo("http://homeassistant.local:8095")
        assertThat(valid("192.168.1.41").url).isEqualTo("http://192.168.1.41:8095")
    }

    @Test
    fun `an https address keeps the scheme port out of the URL`() {
        assertThat(valid("https://ma.example.com", "443").url).isEqualTo("https://ma.example.com")
        assertThat(valid("https://ma.example.com").url).isEqualTo("https://ma.example.com")
    }

    @Test
    fun `a port in the address wins over the port field`() {
        assertThat(valid("http://192.168.1.41:8095", "80").url).isEqualTo("http://192.168.1.41:8095")
    }

    @Test
    fun `a reverse proxy path is kept`() {
        assertThat(valid("https://example.com/music/", "443").url).isEqualTo("https://example.com/music")
        assertThat(valid("https://example.com:8443/music").url).isEqualTo("https://example.com:8443/music")
    }

    @Test
    fun `a space is refused instead of crashing the probe`() {
        assertThat(parseServerAddress("home assistant.local", "8095"))
            .isEqualTo(ServerAddress.Invalid(ADDRESS_HAS_SPACE_MESSAGE))
    }

    @Test
    fun `a bad port or scheme is refused`() {
        assertThat(parseServerAddress("homeassistant.local", "80a"))
            .isEqualTo(ServerAddress.Invalid(PORT_INVALID_MESSAGE))
        assertThat(parseServerAddress("homeassistant.local", "70000"))
            .isEqualTo(ServerAddress.Invalid(PORT_INVALID_MESSAGE))
        assertThat(parseServerAddress("ftp://homeassistant.local", "8095"))
            .isEqualTo(ServerAddress.Invalid(ADDRESS_SCHEME_MESSAGE))
        assertThat(parseServerAddress("http://:8095", "8095"))
            .isEqualTo(ServerAddress.Invalid(ADDRESS_INVALID_MESSAGE))
        assertThat(parseServerAddress("  ", "8095")).isEqualTo(ServerAddress.Empty)
    }

    @Test
    fun `a slip in the scheme is refused rather than read as a host`() {
        for (typed in listOf("http:/192.168.1.5", "http//192.168.1.5", "https:ma.example.com")) {
            assertThat(parseServerAddress(typed, "8095")).isEqualTo(ServerAddress.Invalid(ADDRESS_SCHEME_MESSAGE))
        }
    }

    @Test
    fun `a ws URL from an older version still loads`() {
        assertThat(valid("ws://192.168.1.5:8095").url).isEqualTo("http://192.168.1.5:8095")
        assertThat(valid("wss://ma.example.com").url).isEqualTo("https://ma.example.com")
    }

    @Test
    fun `no http suggestion for an https Home Assistant address`() {
        assertThat(musicAssistantCandidate(valid("https://myha.example.org:8123"))).isNull()
    }

    @Test
    fun `a pasted URL without a port resets an earlier pasted port`() {
        val input = ServerAddressInput().withAddress("http://homeassistant.local:8123").withAddress("")
            .withAddress("https://abc.example.org")
        assertThat(input.port).isEqualTo("443")
    }

    @Test
    fun `a half typed IPv6 address has no port yet`() {
        assertThat(ServerAddressInput(address = "[2001:db8::1").portInAddress).isNull()
    }

    @Test
    fun `the HTTPS chip leaves the address as typed`() {
        val input = ServerAddressInput().withAddress("music.example.com").withPortChoice(PortChoice.HTTPS)
        assertThat(input.address).isEqualTo("music.example.com")
        assertThat(input.secure).isTrue()
    }

    @Test
    fun `a custom port keeps the HTTPS chip's scheme`() {
        val input = ServerAddressInput().withAddress("music.example.com")
            .withPortChoice(PortChoice.HTTPS)
            .withPort("8443")
        assertThat(input.portChoice).isEqualTo(PortChoice.CUSTOM)
        assertThat((input.toServerAddress() as ServerAddress.Valid).url).isEqualTo("https://music.example.com:8443")
    }

    /** Types [text] one character at a time, the way the keyboard reaches the field. */
    private fun ServerAddressInput.type(text: String): ServerAddressInput =
        text.fold(this) { input, char -> input.withAddress(input.address + char) }

    @Test
    fun `a scheme typed after the HTTPS chip picks the Music Assistant port`() {
        val input = ServerAddressInput().withPortChoice(PortChoice.HTTPS).type("http://192.168.1.20")
        assertThat(input.secure).isFalse()
        assertThat(input.portChoice).isEqualTo(PortChoice.MUSIC_ASSISTANT)
        assertThat((input.toServerAddress() as ServerAddress.Valid).url).isEqualTo("http://192.168.1.20:8095")
    }

    @Test
    fun `https typed after the HTTP chip picks port 443`() {
        val input = ServerAddressInput().withPortChoice(PortChoice.HTTP).type("https://music.example.com")
        assertThat(input.portChoice).isEqualTo(PortChoice.HTTPS)
        assertThat((input.toServerAddress() as ServerAddress.Valid).url).isEqualTo("https://music.example.com")
    }

    @Test
    fun `a half typed scheme under the HTTPS chip is not checked as a host`() {
        val chosen = ServerAddressInput().withPortChoice(PortChoice.HTTPS)
        for (typed in listOf("h", "http", "https:")) {
            assertThat(chosen.type(typed).toServerAddress()).isEqualTo(ServerAddress.Empty)
        }
        assertThat(chosen.type("http:/192.168.1.5").toServerAddress()).isInstanceOf(ServerAddress.Invalid::class.java)
    }

    @Test
    fun `pasting a shorter URL over a longer one drops the old port`() {
        val first = ServerAddressInput().withAddress("http://a.example.org:8123")
        val replaced = first.withAddress("https://b.lan")
        assertThat((replaced.toServerAddress() as ServerAddress.Valid).url).isEqualTo("https://b.lan")
    }

    @Test
    fun `the HTTPS chip on an empty field is still empty`() {
        assertThat(ServerAddressInput().withPortChoice(PortChoice.HTTPS).toServerAddress()).isEqualTo(ServerAddress.Empty)
    }

    @Test
    fun `a new address starts on the Music Assistant chip`() {
        assertThat(ServerAddressInput().portChoice).isEqualTo(PortChoice.MUSIC_ASSISTANT)
        assertThat(ServerAddressInput().withAddress("192.168.1.20").portChoice).isEqualTo(PortChoice.MUSIC_ASSISTANT)
    }

    @Test
    fun `typing the scheme or a port moves the chip`() {
        assertThat(ServerAddressInput().withAddress("https://music.example.com").portChoice).isEqualTo(PortChoice.HTTPS)
        assertThat(ServerAddressInput().withAddress("192.168.1.20:8096").portChoice).isEqualTo(PortChoice.CUSTOM)
        assertThat(ServerAddressInput().withAddress("https://music.example.com:8443").portChoice).isEqualTo(PortChoice.CUSTOM)
    }

    @Test
    fun `the HTTPS chip sets the scheme and port 443`() {
        val input = ServerAddressInput().withAddress("music.example.com").withPortChoice(PortChoice.HTTPS)
        assertThat(input.portChoice).isEqualTo(PortChoice.HTTPS)
        assertThat((input.toServerAddress() as ServerAddress.Valid).url).isEqualTo("https://music.example.com")
    }

    @Test
    fun `the HTTP chip uses port 80 without a scheme`() {
        val input = ServerAddressInput().withAddress("music.example.com").withPortChoice(PortChoice.HTTP)
        assertThat(input.portChoice).isEqualTo(PortChoice.HTTP)
        assertThat((input.toServerAddress() as ServerAddress.Valid).url).isEqualTo("http://music.example.com")
    }

    @Test
    fun `the Music Assistant chip undoes a typed scheme and port`() {
        val input = ServerAddressInput().withAddress("https://192.168.1.20:8443").withPortChoice(PortChoice.MUSIC_ASSISTANT)
        assertThat(input.portChoice).isEqualTo(PortChoice.MUSIC_ASSISTANT)
        assertThat((input.toServerAddress() as ServerAddress.Valid).url).isEqualTo("http://192.168.1.20:8095")
    }

    @Test
    fun `the Custom chip keeps the address and its port`() {
        val input = ServerAddressInput().withAddress("192.168.1.20")
        assertThat(input.withPortChoice(PortChoice.CUSTOM)).isEqualTo(input)
    }

    @Test
    fun `the port field replaces a port written in the address`() {
        val input = ServerAddressInput(address = "http://homeassistant.local:8123").withPort("8095")
        assertThat(input.address).isEqualTo("http://homeassistant.local")
        assertThat(input.effectivePort).isEqualTo("8095")
        assertThat((input.toServerAddress() as ServerAddress.Valid).url).isEqualTo("http://homeassistant.local:8095")
    }

    @Test
    fun `a scheme still being typed is not an address yet`() {
        for (typed in listOf("h", "htt", "http:", "http:/", "http://", "https://")) {
            assertThat(parseServerAddress(typed, "8095")).isEqualTo(ServerAddress.Empty)
        }
    }

    @Test
    fun `Home Assistant addresses are recognised`() {
        assertThat(valid("http://homeassistant.local:8123").homeAssistant)
            .isEqualTo(HomeAssistantAddress.HOME_ASSISTANT)
        assertThat(valid("https://abcdef.ui.nabu.casa").homeAssistant)
            .isEqualTo(HomeAssistantAddress.NABU_CASA)
        assertThat(valid("https://ha.example.com/api/hassio_ingress/xyz").homeAssistant)
            .isEqualTo(HomeAssistantAddress.INGRESS)
        assertThat(valid("http://192.168.1.41:8123/d5369777_music_assistant").homeAssistant)
            .isEqualTo(HomeAssistantAddress.INGRESS)
        assertThat(valid("homeassistant.local", "8095").homeAssistant).isNull()
    }

    @Test
    fun `the Music Assistant candidate for a Home Assistant address is the same host on 8095`() {
        assertThat(musicAssistantCandidate(valid("http://homeassistant.local:8123")))
            .isEqualTo("http://homeassistant.local:8095")
        assertThat(musicAssistantCandidate(valid("https://abcdef.ui.nabu.casa"))).isNull()
    }

    @Test
    fun `the port follows the scheme until the user edits it`() {
        var input = ServerAddressInput()
        input = input.withAddress("h")
        assertThat(input.port).isEqualTo("8095")
        input = input.withAddress("https://ma.example.com")
        assertThat(input.port).isEqualTo("443")
        input = input.withPort("8443").withAddress("https://ma.example.co")
        assertThat(input.port).isEqualTo("8443")
    }

    @Test
    fun `a pasted URL moves its port into the port field, a typed one stays`() {
        val pasted = ServerAddressInput().withAddress("http://homeassistant.local:8123")
        assertThat(pasted.address).isEqualTo("http://homeassistant.local")
        assertThat(pasted.port).isEqualTo("8123")

        val typed = ServerAddressInput(address = "homeassistant.local:809").withAddress("homeassistant.local:8095")
        assertThat(typed.address).isEqualTo("homeassistant.local:8095")
        assertThat(typed.portInAddress).isEqualTo("8095")
    }

    @Test
    fun `a saved URL splits into the two fields and reads back the same`() {
        for (url in listOf(
            "http://192.168.1.41:8095",
            "https://ma.example.com",
            "http://ma.lan",
            "https://example.com:8443/music",
        )) {
            val input = ServerAddressInput.fromUrl(url)
            assertThat((input.toServerAddress() as ServerAddress.Valid).url).isEqualTo(url)
        }
        assertThat(ServerAddressInput.fromUrl("http://192.168.1.41:8095"))
            .isEqualTo(ServerAddressInput("http://192.168.1.41", "8095", portEdited = true))
    }
}
