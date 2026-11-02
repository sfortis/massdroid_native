package net.asksakis.massdroidv2.domain.connection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Step 1 of the sign-in lists the server used last and the servers found on the network. A
 * server that is both must show once, or the user has to guess which of the two rows to pick.
 */
class ServerRowsTest {

    private val addon = AnnouncedServer("http://192.168.1.41:8095", "Home Assistant add-on", "2.10.9", true)
    private val mass = AnnouncedServer("http://192.168.1.14:8095", "mass", "2.10.9", false)

    @Test
    fun `a last used server that was not found gets its own row first`() {
        val rows = serverRows("https://ma.example.com", listOf(addon, mass))
        assertThat(rows.map { it.group }).containsExactly(
            ServerRowGroup.LAST_USED, ServerRowGroup.ON_THIS_NETWORK, ServerRowGroup.ON_THIS_NETWORK
        ).inOrder()
        assertThat(rows.first().name).isEqualTo("ma.example.com")
    }

    @Test
    fun `a last used server that was found shows once and says it was used last`() {
        val rows = serverRows("http://192.168.1.14:8095/", listOf(addon, mass))
        assertThat(rows).hasSize(2)
        assertThat(rows.single { it.name == "mass" }.detail).isEqualTo("Last used · 192.168.1.14:8095 · v2.10.9")
        assertThat(rows.single { it.name == "Home Assistant add-on" }.detail).isEqualTo("192.168.1.41:8095 · v2.10.9")
    }

    @Test
    fun `nothing saved and nothing found leaves the list empty`() {
        assertThat(serverRows("", emptyList())).isEmpty()
    }
}
