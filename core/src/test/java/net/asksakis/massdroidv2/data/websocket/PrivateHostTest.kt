package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * isPrivateHost moved out of MaWebSocketClient unchanged, to also tell whether a Party join
 * link opens only on the home network. The client skips the TLS name check on these hosts.
 */
class PrivateHostTest {

    @Test
    fun `private and loopback addresses are private`() {
        listOf("10.0.0.5", "172.16.1.1", "172.31.255.255", "192.168.1.20", "127.0.0.1", "100.64.0.1", "100.127.1.1")
            .forEach { assertWithMessage(it).that(isPrivateHost(it)).isTrue() }
    }

    @Test
    fun `local names are private`() {
        listOf("localhost", "music.local", "nas.lan", "box.tail1234.ts.net")
            .forEach { assertWithMessage(it).that(isPrivateHost(it)).isTrue() }
    }

    @Test
    fun `public addresses and names are not`() {
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1", "mass.example.com", "app.music-assistant.io", "10.0.0")
            .forEach { assertWithMessage(it).that(isPrivateHost(it)).isFalse() }
    }
}
