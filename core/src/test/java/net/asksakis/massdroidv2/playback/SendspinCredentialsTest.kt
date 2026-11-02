package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.sendspin.SendspinClient
import net.asksakis.massdroidv2.data.websocket.AuthenticatedSession
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.ServerInfo
import org.junit.Test

/**
 * After a server change the S25 sent Sendspin to the previous server's address with the new
 * server's token: the address came from the settings, which the sign-in writes only after the
 * connection is up, and the token from the live connection (2026-11-02).
 */
class SendspinCredentialsTest {

    private val sessionB = AuthenticatedSession("http://b:8095", "token B", "B")
    private val sessionA = AuthenticatedSession("http://a:8095", "token A", "A")
    private val connectedB = ConnectionState.Connected(ServerInfo("B", "2.10.9", 28, "http://b"), 7, sessionB)

    private fun credentials(state: ConnectionState, last: AuthenticatedSession? = null, planned: String) =
        sendspinCredentials(state, last, planned)

    @Test
    fun `while MA is connected, the address and token come from that connection together`() {
        assertThat(credentials(connectedB, last = sessionB, planned = "B"))
            .isEqualTo(SendspinClient.Credentials("http://b:8095", "token B"))
    }

    @Test
    fun `while MA is down, the last connection of the process is used, not the saved pair`() {
        // The saved address and token are written to two stores one after the other; a read in
        // between could pair A's address with B's token, so they are never used.
        assertThat(credentials(ConnectionState.Disconnected, last = sessionB, planned = "B"))
            .isEqualTo(SendspinClient.Credentials("http://b:8095", "token B"))
    }

    @Test
    fun `a start planned for one server does not connect to another`() {
        // The old controller and the coordinator react to the same Connected of server B; the
        // old controller's plan, player id and protocol belong to A.
        assertThat(credentials(connectedB, last = sessionB, planned = "A")).isNull()
        assertThat(credentials(ConnectionState.Disconnected, last = sessionB, planned = "A")).isNull()
        assertThat(credentials(ConnectionState.Disconnected, last = sessionA, planned = "A"))
            .isEqualTo(SendspinClient.Credentials("http://a:8095", "token A"))
    }

    @Test
    fun `a planned start with no session left sends nothing, not the saved token`() {
        // A plan exists only after MA connected; no live and no last session means Sign out or a
        // refused token, and the saved token is stale or about to be blanked.
        assertThat(credentials(ConnectionState.Disconnected, last = null, planned = "A")).isNull()
    }

    @Test
    fun `the credentials never print their token`() {
        assertThat(SendspinClient.Credentials("http://b:8095", "token B").toString()).doesNotContain("token B")
    }
}
