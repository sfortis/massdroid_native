package net.asksakis.massdroidv2.domain.connection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A tester over Tailscale tapped "Sign in with Home Assistant" and the browser kept opening a
 * LAN address. The app now checks the addresses in the link before it opens the browser.
 */
class HomeAssistantSignInLinkTest {

    private val link = "https://ha.example.org/auth/authorize?response_type=code" +
        "&redirect_uri=http%3A%2F%2F192.168.1.41%3A8095%2Fauth%2Fcallback%3Fprovider_id%3Dhomeassistant" +
        "&client_id=http%3A%2F%2F192.168.1.41%3A8095&state=abc"

    @Test
    fun `the login page and the return address are checked when the app is elsewhere`() {
        assertThat(signInOriginsToCheck(link, "http://100.64.1.2:8095"))
            .containsExactly("https://ha.example.org", "http://192.168.1.41:8095").inOrder()
    }

    @Test
    fun `the address the app is connected to is not checked again`() {
        assertThat(signInOriginsToCheck(link, "http://192.168.1.41:8095/"))
            .containsExactly("https://ha.example.org")
    }

    @Test
    fun `a link that is not a URL checks nothing`() {
        assertThat(signInOriginsToCheck("not a link", "http://192.168.1.41:8095")).isEmpty()
    }
}
