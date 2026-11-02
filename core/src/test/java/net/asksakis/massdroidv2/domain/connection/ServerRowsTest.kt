package net.asksakis.massdroidv2.domain.connection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Step 1 of the sign-in lists the servers used before and the servers found on the network. A
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
    fun `every server used before is listed, the last used first`() {
        val rows = serverRows(
            "https://ma.example.com",
            emptyList(),
            usedBefore = listOf("https://ma.example.com/", "https://office.example.com")
        )
        assertThat(rows.map { it.url }).containsExactly("https://ma.example.com", "https://office.example.com").inOrder()
        assertThat(rows.map { it.detail }).containsExactly("Last used", "Used before").inOrder()
        assertThat(rows.map { it.group }.toSet()).containsExactly(ServerRowGroup.LAST_USED)
    }

    @Test
    fun `only servers used before can be forgotten, not the current one`() {
        val rows = serverRows("https://ma.example.com", emptyList(), usedBefore = listOf("https://office.example.com"))
        assertThat(rows.associate { it.url to it.canForget }).containsExactly(
            "https://ma.example.com", false,
            "https://office.example.com", true
        )
    }

    @Test
    fun `a server used before that was found shows once and says so`() {
        val rows = serverRows("https://ma.example.com", listOf(addon, mass), usedBefore = listOf("http://192.168.1.14:8095"))
        assertThat(rows.map { it.url }).containsExactly(
            "https://ma.example.com", "http://192.168.1.41:8095", "http://192.168.1.14:8095"
        ).inOrder()
        assertThat(rows.single { it.name == "mass" }.detail).isEqualTo("Used before · 192.168.1.14:8095 · v2.10.9")
    }

    @Test
    fun `nothing saved and nothing found leaves the list empty`() {
        assertThat(serverRows("", emptyList())).isEmpty()
    }
}
