package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Any app or web page can open a musicassistant://auth link. Only the link carrying the
 * nonce of the sign-in in progress may replace the saved token.
 */
class OAuthCallbackMatchTest {

    @Test
    fun `the callback of the sign-in in progress is accepted`() {
        assertThat(oauthCallbackMatches(expected = "a1b2", received = "a1b2")).isTrue()
    }

    @Test
    fun `a callback with another nonce is refused`() {
        assertThat(oauthCallbackMatches(expected = "a1b2", received = "ffff")).isFalse()
    }

    /** A link without a nonce, such as one built before this check, is not ours. */
    @Test
    fun `a callback without a nonce is refused`() {
        assertThat(oauthCallbackMatches(expected = "a1b2", received = null)).isFalse()
    }

    /** No sign-in in progress, for example after the process was killed in the browser step. */
    @Test
    fun `a callback with no sign-in in progress is refused`() {
        assertThat(oauthCallbackMatches(expected = null, received = "a1b2")).isFalse()
    }
}
