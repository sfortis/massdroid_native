package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The link a Home Assistant sign-in ends on. The phone receives it as a deep link and the TV and
 * car catch it inside their sign-in page, and both read it with [parseOAuthCallback].
 */
class OAuthCallbackParseTest {

    @Test
    fun `the token and the sign-in's nonce are read`() {
        assertThat(parseOAuthCallback("musicassistant://auth/callback/n1?code=eyJ.abc_def-1"))
            .isEqualTo(OAuthCallback(state = "n1", code = "eyJ.abc_def-1"))
    }

    @Test
    fun `an encoded token is decoded and other parameters are skipped`() {
        assertThat(parseOAuthCallback("musicassistant://auth/callback/n1?x=1&code=a%2Fb%3D"))
            .isEqualTo(OAuthCallback(state = "n1", code = "a/b="))
    }

    @Test
    fun `a link without the nonce has no state`() {
        assertThat(parseOAuthCallback("musicassistant://auth/callback?code=tok"))
            .isEqualTo(OAuthCallback(state = null, code = "tok"))
    }

    @Test
    fun `anything that is not a sign-in callback with a token is refused`() {
        assertThat(parseOAuthCallback("https://ha.lan:8123/auth/authorize?code=tok")).isNull()
        assertThat(parseOAuthCallback("musicassistant://player/callback/n1?code=tok")).isNull()
        assertThat(parseOAuthCallback("musicassistant://auth/callback/n1?code=")).isNull()
        assertThat(parseOAuthCallback("musicassistant://auth/callback/n1")).isNull()
        assertThat(parseOAuthCallback("musicassistant://auth/callback/n 1?code=tok")).isNull()
    }
}
