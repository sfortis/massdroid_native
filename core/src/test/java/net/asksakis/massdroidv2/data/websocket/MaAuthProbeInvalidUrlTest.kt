package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * The sign-in form probes the typed address while the user types. A host with a space, which
 * the keyboard's autocorrect produces from "homeassistant", made OkHttp throw, and the
 * exception ended the app (Mi MIX, 2.39.2).
 */
class MaAuthProbeInvalidUrlTest {

    private val probe = MaAuthProbe({ OkHttpClient() }, Json { ignoreUnknownKeys = true })

    @Test
    fun `a host with a space is not Music Assistant instead of throwing`() = runTest {
        assertThat(probe.identify("http://home assistant.local:8095")).isInstanceOf(ServerAnswer.Failed::class.java)
    }

    @Test
    fun `a host with a space cannot start a Home Assistant sign-in`() = runTest {
        assertThat(probe.fetchAuthorizationUrl("http://home assistant.", "homeassistant", "musicassistant://x"))
            .isNull()
    }
}
