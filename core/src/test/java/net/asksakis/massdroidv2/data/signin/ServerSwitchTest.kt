package net.asksakis.massdroidv2.data.signin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Signing in to the home address of the server already used through its remote address dropped
 * the selected player and the Discover feed as if it were another server (S25, 2026-11-02).
 */
class ServerSwitchTest {

    @Test
    fun `another address of the same server is not a switch`() {
        assertThat(isOtherServer("https://music.example.org", "home", "http://192.168.1.14:8095", "home")).isFalse()
    }

    @Test
    fun `another server is a switch, even at the same address`() {
        assertThat(isOtherServer("http://192.168.1.14:8095", "home", "http://192.168.1.14:8095", "office")).isTrue()
    }

    @Test
    fun `without both server ids the address decides`() {
        assertThat(isOtherServer("http://home:8095", null, "http://home:8095/", "home")).isFalse()
        assertThat(isOtherServer("http://home:8095", "home", "http://office:8095", null)).isTrue()
    }
}
