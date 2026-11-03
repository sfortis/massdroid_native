package net.asksakis.massdroidv2.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The per-server accounts and the addresses of the device store, on plain preferences: one
 * account per server, one entry per address it was used through, what forgetting an address
 * removes, and the one-time moves of older saved data.
 */
class ServerAccountsStoreTest {

    private val authToken = stringPreferencesKey("auth_token")
    private val username = stringPreferencesKey("username")
    private val trusted = stringPreferencesKey("trusted_server_id")
    private val migrated = booleanPreferencesKey("server_accounts_migrated")
    private fun token(id: String) = stringPreferencesKey("auth_token@$id")
    private fun user(id: String) = stringPreferencesKey("username@$id")
    private fun address(url: String) = stringPreferencesKey("addr@$url")
    private fun addressUsedAt(url: String) = longPreferencesKey("addr_used_at@$url")
    private fun legacyUrl(id: String) = stringPreferencesKey("url@$id")
    private fun legacyUsedAt(id: String) = longPreferencesKey("used_at@$id")

    private val remote = "https://mass.example.org"
    private val home = "http://192.168.1.14:8095"

    @Test
    fun `each address of one server keeps its own entry and time of use`() {
        // The owner signed in through the remote address, then through the home one; the
        // remote one used to disappear from Recently used.
        val prefs = mutablePreferencesOf()
        prefs.putServerEntry("mass", remote, "token", "alice", usedAtMs = 1)
        prefs.putServerEntry("mass", "$home/", "token", "alice", usedAtMs = 2)

        assertThat(prefs[address(remote)]).isEqualTo("mass")
        assertThat(prefs[address(home)]).isEqualTo("mass")
        assertThat(prefs[addressUsedAt(remote)]).isEqualTo(1)
        assertThat(prefs[addressUsedAt(home)]).isEqualTo(2)
        assertThat(prefs[token("mass")]).isEqualTo("token")
    }

    @Test
    fun `another server saved at the same address takes the address and leaves the first one's token`() {
        // A friend's server and one's own can share a private address on different networks.
        val prefs = mutablePreferencesOf()
        prefs.putServerEntry("home", home, "home token", "alice", usedAtMs = 1)
        prefs.putServerEntry("friend", home, "friend token", "bob", usedAtMs = 2)

        assertThat(prefs[address(home)]).isEqualTo("friend")
        assertThat(prefs[token("home")]).isEqualTo("home token")
        assertThat(prefs[user("home")]).isEqualTo("alice")
    }

    @Test
    fun `forgetting one address of a server keeps its account while it has another`() {
        val prefs = mutablePreferencesOf(trusted to "other")
        prefs.putServerEntry("mass", remote, "token", "alice", usedAtMs = 1)
        prefs.putServerEntry("mass", home, "token", "alice", usedAtMs = 2)
        prefs.forgetServerAt(remote, serverId = null, savedUrl = "http://other:8095")

        assertThat(prefs[address(remote)]).isNull()
        assertThat(prefs[addressUsedAt(remote)]).isNull()
        assertThat(prefs[address(home)]).isEqualTo("mass")
        assertThat(prefs[token("mass")]).isEqualTo("token")
    }

    @Test
    fun `forgetting the last address of a server that is not the current one forgets its account`() {
        val prefs = mutablePreferencesOf(trusted to "other")
        prefs.putServerEntry("office", "http://office:8095", "office token", "bob", usedAtMs = 1)
        prefs.forgetServerAt("http://office:8095/", serverId = null, savedUrl = "http://other:8095")

        assertThat(prefs[address("http://office:8095")]).isNull()
        assertThat(prefs[token("office")]).isNull()
        assertThat(prefs[user("office")]).isNull()
    }

    @Test
    fun `the current server keeps its account when its other address is forgotten`() {
        val prefs = mutablePreferencesOf(trusted to "mass")
        prefs.putServerEntry("mass", remote, "token", "alice", usedAtMs = 1)
        prefs.putServerEntry("mass", home, "token", "alice", usedAtMs = 2)
        prefs.forgetServerAt(remote, serverId = null, savedUrl = home)

        assertThat(prefs[address(remote)]).isNull()
        assertThat(prefs[token("mass")]).isEqualTo("token")
    }

    @Test
    fun `the current address is never forgotten`() {
        val prefs = mutablePreferencesOf(trusted to "mass")
        prefs.putServerEntry("mass", home, "token", "alice", usedAtMs = 1)
        prefs.forgetServerAt(home, serverId = null, savedUrl = "$home/")

        assertThat(prefs[address(home)]).isEqualTo("mass")
        assertThat(prefs[token("mass")]).isEqualTo("token")
    }

    @Test
    fun `the current server keeps its account when its only stored address is forgotten`() {
        // The trusted server is now used through another address that was never stored.
        val prefs = mutablePreferencesOf(trusted to "mass")
        prefs.putServerEntry("mass", remote, "token", "alice", usedAtMs = 1)
        prefs.forgetServerAt(remote, serverId = null, savedUrl = home)

        assertThat(prefs[address(remote)]).isNull()
        assertThat(prefs[token("mass")]).isEqualTo("token")
        assertThat(prefs[user("mass")]).isEqualTo("alice")
    }

    @Test
    fun `Forget removes the accounts of the answering server and the saved one when no address is left`() {
        // The address was saved for B on another network; at home A answers there, and the
        // form showed A's account. Neither has another address, so neither token stays behind.
        val prefs = mutablePreferencesOf(trusted to "other")
        prefs.putServerEntry("A", home, "a token", "alice", usedAtMs = 1)
        prefs.putServerEntry("B", home, "b token", "bob", usedAtMs = 2)
        prefs.forgetServerAt(home, serverId = "A", savedUrl = "http://other:8095")

        assertThat(prefs[address(home)]).isNull()
        assertThat(prefs[token("A")]).isNull()
        assertThat(prefs[user("A")]).isNull()
        assertThat(prefs[token("B")]).isNull()
        assertThat(prefs[user("B")]).isNull()
    }

    @Test
    fun `the server the address was saved for keeps its account while another address leads to it`() {
        val prefs = mutablePreferencesOf(trusted to "other")
        prefs.putServerEntry("A", home, "a token", "alice", usedAtMs = 1)
        prefs.putServerEntry("B", remote, "b token", "bob", usedAtMs = 2)
        prefs.putServerEntry("B", home, "b token", "bob", usedAtMs = 3)
        prefs.forgetServerAt(home, serverId = "A", savedUrl = "http://other:8095")

        assertThat(prefs[token("A")]).isNull()
        assertThat(prefs[token("B")]).isEqualTo("b token")
        assertThat(prefs[address(remote)]).isEqualTo("B")
    }

    @Test
    fun `when the current server answers, only the server the address was saved for is forgotten`() {
        val prefs = mutablePreferencesOf(trusted to "T")
        prefs.putServerEntry("T", remote, "t token", "alice", usedAtMs = 1)
        prefs.putServerEntry("S", home, "s token", "bob", usedAtMs = 2)
        prefs.forgetServerAt(home, serverId = "T", savedUrl = remote)

        assertThat(prefs[token("S")]).isNull()
        assertThat(prefs[token("T")]).isEqualTo("t token")
    }

    @Test
    fun `a key an earlier build stored with its default port is found as the same address`() {
        val prefs = mutablePreferencesOf(trusted to "other")
        prefs[address("https://mass.example.org:443")] = "S"
        prefs[token("S")] = "s token"
        prefs.forgetServerAt("https://mass.example.org", serverId = null, savedUrl = "http://other:8095")
        assertThat(prefs.usedAddresses()).isEmpty()
        assertThat(prefs[token("S")]).isNull()

        prefs[address("https://mass.example.org:443")] = "S"
        prefs.putServerEntry("S", "https://mass.example.org", "s token", "bob", usedAtMs = 2)
        assertThat(prefs.usedAddresses()).containsExactly("https://mass.example.org")
    }

    @Test
    fun `an address that does not answer is forgotten by the server it was saved for`() {
        val prefs = mutablePreferencesOf(trusted to "other")
        prefs.putServerEntry("A", home, "a token", "alice", usedAtMs = 1)
        prefs.putServerEntry("B", home, "b token", "bob", usedAtMs = 2)
        prefs.forgetServerAt(home, serverId = null, savedUrl = "http://other:8095")

        assertThat(prefs[token("B")]).isNull()
        assertThat(prefs[token("A")]).isEqualTo("a token")
    }

    @Test
    fun `nothing is forgotten when the saved address could not be read`() {
        val prefs = mutablePreferencesOf(trusted to "other")
        prefs.putServerEntry("office", "http://office:8095", "office token", "bob", usedAtMs = 1)
        prefs.forgetServerAt("http://office:8095", serverId = null, savedUrl = "")

        assertThat(prefs[address("http://office:8095")]).isEqualTo("office")
        assertThat(prefs[token("office")]).isEqualTo("office token")
    }

    @Test
    fun `addresses that differ only in case or in a default port are one entry`() {
        val prefs = mutablePreferencesOf(trusted to "other")
        prefs.putServerEntry("mass", "https://Mass.Example.org:443/", "token", "alice", usedAtMs = 1)
        prefs.putServerEntry("mass", "https://mass.example.org", "token", "alice", usedAtMs = 2)
        assertThat(prefs.usedAddresses()).containsExactly("https://mass.example.org")

        prefs.forgetServerAt("HTTPS://MASS.EXAMPLE.ORG", serverId = null, savedUrl = "http://other:8095")
        assertThat(prefs.usedAddresses()).isEmpty()
    }

    @Test
    fun `used addresses come most recently used first`() {
        val prefs = mutablePreferencesOf()
        prefs.putServerEntry("a", "http://a:8095", "t", "u", usedAtMs = 1)
        prefs.putServerEntry("b", "http://b:8095", "t", "u", usedAtMs = 3)
        prefs.putServerEntry("c", "http://c:8095", "t", "u", usedAtMs = 2)
        assertThat(prefs.usedAddresses()).containsExactly("http://b:8095", "http://c:8095", "http://a:8095").inOrder()
    }

    @Test
    fun `a refused token is forgotten only where it is still the saved one`() {
        val prefs = mutablePreferencesOf(authToken to "new token")
        prefs.putServerEntry("home", home, "new token", "alice", usedAtMs = 1)
        prefs.forgetRejectedToken("home", "old token")
        assertThat(prefs[authToken]).isEqualTo("new token")
        assertThat(prefs[token("home")]).isEqualTo("new token")

        prefs.forgetRejectedToken("home", "new token")
        assertThat(prefs[authToken]).isEmpty()
        assertThat(prefs[token("home")]).isNull()
        assertThat(prefs[user("home")]).isEqualTo("alice")
    }

    @Test
    fun `the saved account moves to its server's entry once`() {
        val prefs = mutablePreferencesOf(trusted to "home", authToken to "home token", username to "alice")
        prefs.migrateToServerAccounts(home)
        assertThat(prefs[token("home")]).isEqualTo("home token")
        assertThat(prefs[address(home)]).isEqualTo("home")
        assertThat(prefs[migrated]).isTrue()

        // A later token that belongs to another server is not copied over the entry again.
        prefs[authToken] = "office token"
        prefs.remove(token("home"))
        prefs.migrateToServerAccounts(home)
        assertThat(prefs[token("home")]).isNull()
    }

    @Test
    fun `the one address per server of the first builds becomes that server's first address`() {
        val prefs = mutablePreferencesOf(legacyUrl("mass") to "$home/", legacyUsedAt("mass") to 5L)
        prefs.migrateToServerAccounts("")

        assertThat(prefs[address(home)]).isEqualTo("mass")
        assertThat(prefs[addressUsedAt(home)]).isEqualTo(5)
        assertThat(prefs[legacyUrl("mass")]).isNull()
        assertThat(prefs[legacyUsedAt("mass")]).isNull()
    }

    @Test
    fun `the moves mark themselves done even with nothing to move`() {
        val prefs = mutablePreferencesOf()
        prefs.migrateToServerAccounts("")
        assertThat(prefs[migrated]).isTrue()
        assertThat(prefs[booleanPreferencesKey("server_addresses_migrated")]).isTrue()
    }
}
