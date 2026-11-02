package net.asksakis.massdroidv2.data.signin

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.connection.sameServer
import net.asksakis.massdroidv2.domain.model.ServerAccount
import org.junit.Test

/**
 * The sign-in form for server B used to show the username and password saved for server A
 * (code check finding 7). The password now belongs to the current server only, and each server
 * offers its own saved account.
 */
class SavedSignInTest {

    private val home = CurrentAccount(url = "http://home:8095", serverId = "home", username = "alice", password = "secret")

    private fun formFor(
        server: String,
        serverId: String?,
        saved: ServerAccount? = null,
        current: CurrentAccount = home
    ) = savedSignInFor(server, serverId, current, saved, nameOf = { it.username.ifBlank { "named by token" } })

    @Test
    fun `the current server offers its username and password`() {
        val form = formFor("http://home:8095", "home")
        assertThat(form.username).isEqualTo("alice")
        assertThat(form.password).isEqualTo("secret")
        assertThat(form.passwordServerId).isEqualTo("home")
    }

    @Test
    fun `a password offered before the check answered still names its server`() {
        // The row still says Checking: the address decides, and the password stays bound to
        // the server it was saved for, so another machine at that address does not get it.
        val form = formFor("http://home:8095", serverId = null)
        assertThat(form.password).isEqualTo("secret")
        assertThat(form.passwordServerId).isEqualTo("home")
    }

    @Test
    fun `another server never sees the current server's password or username`() {
        val form = formFor("http://office:8095", "office")
        assertThat(form.password).isEmpty()
        assertThat(form.username).isEmpty()
        assertThat(form.continueAs).isNull()
        assertThat(form.passwordServerId).isNull()
    }

    @Test
    fun `another server offers its own saved account, without a password`() {
        val form = formFor("http://office:8095", "office", saved = ServerAccount(token = "t", username = "bob"))
        assertThat(form.username).isEqualTo("bob")
        assertThat(form.password).isEmpty()
        assertThat(form.continueAs).isEqualTo("bob")
        assertThat(form.serverId).isEqualTo("office")
    }

    @Test
    fun `a saved username without a token offers no Continue as`() {
        val form = formFor("http://office:8095", "office", saved = ServerAccount(token = "", username = "bob"))
        assertThat(form.username).isEqualTo("bob")
        assertThat(form.continueAs).isNull()
    }

    @Test
    fun `the current server at another address is still the current server`() {
        // The same server reached through its remote address: the server_id decides.
        val form = formFor("https://music.example.org", "home")
        assertThat(form.password).isEqualTo("secret")
    }

    @Test
    fun `another server at the saved address is not the current server`() {
        // A different machine answering at the same private address on another network.
        val form = formFor("http://home:8095", "stranger")
        assertThat(form.password).isEmpty()
    }

    @Test
    fun `with no server known at all, the saved password is not offered`() {
        // Nothing could bind the password to a server, so it would go to whatever answers.
        val beforeUpdate = home.copy(serverId = null)
        val form = formFor("http://home:8095", serverId = null, current = beforeUpdate)
        assertThat(form.password).isEmpty()
        assertThat(form.passwordServerId).isNull()
        assertThat(form.username).isEqualTo("alice")
    }

    @Test
    fun `an address with its default port is the same server`() {
        assertThat(sameServer("https://Music.Example.org:443/", "https://music.example.org")).isTrue()
        assertThat(sameServer("http://home:80", "http://home")).isTrue()
        assertThat(sameServer("http://home:8095", "http://home")).isFalse()
    }

    @Test
    fun `without a server id the address decides`() {
        assertThat(formFor("http://home:8095/", serverId = null).password).isEqualTo("secret")
        assertThat(formFor("http://office:8095", serverId = null).password).isEmpty()
        val beforeUpdate = home.copy(serverId = null)
        assertThat(formFor("http://home:8095", "home", current = beforeUpdate).password).isEqualTo("secret")
    }
}
