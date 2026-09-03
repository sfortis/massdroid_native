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
            "auth/login",
            buildJsonObject {
                put("username", "someuser")
                put("password", "a-real-password-123")
                put("device_name", "MassDroid")
            }
        )

        assertThat(rendered).doesNotContain("a-real-password-123")
        // The username goes too: it identifies the reporter's account and the
        // command name already says a login was attempted.
        assertThat(rendered).doesNotContain("someuser")
        assertThat(rendered).contains("<redacted>")
    }

    @Test
    fun `an auth token never reaches the log`() {
        val rendered = client.redactSecrets(
            "auth",
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
            "players/cmd/volume_set",
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
