package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * What the per-command outbound trace is allowed to write down.
 *
 * `auth/login` sends username, password and device name in one object, about 82
 * characters, so the previous "truncate to 120" left the password in the log
 * whole. That log is what users attach to bug reports, and the Share logs button
 * now sends a day of it, so the value has to be removed rather than clipped.
 */
class CommandLogRedactionTest {

    private val client = MaWebSocketClient(
        baseOkHttpClient = mockk<OkHttpClient>(relaxed = true),
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `a login password never reaches the log`() {
        val rendered = client.redactSecrets(
            buildJsonObject {
                put("username", "someuser")
                put("password", "a-real-password-123")
                put("device_name", "MassDroid")
            }
        )

        assertThat(rendered).doesNotContain("a-real-password-123")
        assertThat(rendered).contains("<redacted>")
        // The username stays: it separates "wrong user" from "wrong password"
        // when someone reports that sign-in fails.
        assertThat(rendered).contains("someuser")
    }

    @Test
    fun `an auth token never reaches the log`() {
        val rendered = client.redactSecrets(
            buildJsonObject {
                put("token", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZm9ydGlzIn0.s1gn4tur3")
                put("device_name", "MassDroid")
            }
        )

        assertThat(rendered).doesNotContain("eyJ")
        assertThat(rendered).contains("<redacted>")
    }

    @Test
    fun `ordinary command arguments are left readable`() {
        val rendered = client.redactSecrets(
            buildJsonObject {
                put("player_id", "5046ad54")
                put("volume_level", 40)
            }
        )

        assertThat(rendered).contains("5046ad54")
        assertThat(rendered).contains("40")
        assertThat(rendered).doesNotContain("<redacted>")
    }
}
