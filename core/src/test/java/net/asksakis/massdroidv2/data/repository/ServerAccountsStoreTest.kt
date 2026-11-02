package net.asksakis.massdroidv2.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The per-server entries of the device store, on plain preferences: what saving one server does
 * to another at the same address, what forgetting removes, and the one-time move of the account
 * saved before tokens were kept per server.
 */
class ServerAccountsStoreTest {

    private val authToken = stringPreferencesKey("auth_token")
    private val username = stringPreferencesKey("username")
    private val trusted = stringPreferencesKey("trusted_server_id")
    private val migrated = booleanPreferencesKey("server_accounts_migrated")
    private fun token(id: String) = stringPreferencesKey("auth_token@$id")
    private fun user(id: String) = stringPreferencesKey("username@$id")
    private fun url(id: String) = stringPreferencesKey("url@$id")
    private fun usedAt(id: String) = longPreferencesKey("used_at@$id")

    @Test
    fun `saving a server at another's address keeps the other's token and drops only its address`() {
        // A friend's server and one's own can share a private address on different networks.
        val prefs = mutablePreferencesOf()
        prefs.putServerEntry("home", "http://192.168.1.14:8095", "home token", "alice", usedAtMs = 1)
        prefs.putServerEntry("friend", "http://192.168.1.14:8095/", "friend token", "bob", usedAtMs = 2)

        assertThat(prefs[token("home")]).isEqualTo("home token")
        assertThat(prefs[user("home")]).isEqualTo("alice")
        assertThat(prefs[url("home")]).isNull()
        assertThat(prefs[usedAt("home")]).isNull()
        assertThat(prefs[url("friend")]).isEqualTo("http://192.168.1.14:8095/")
    }

    @Test
    fun `forgetting a server removes its whole entry`() {
        val prefs = mutablePreferencesOf(trusted to "home")
        prefs.putServerEntry("office", "http://office:8095", "office token", "bob", usedAtMs = 1)
        prefs.forgetServerAt("http://office:8095/")

        assertThat(prefs[token("office")]).isNull()
        assertThat(prefs[user("office")]).isNull()
        assertThat(prefs[url("office")]).isNull()
        assertThat(prefs[usedAt("office")]).isNull()
    }

    @Test
    fun `the current server is never forgotten from the list`() {
        val prefs = mutablePreferencesOf(trusted to "home")
        prefs.putServerEntry("home", "http://home:8095", "home token", "alice", usedAtMs = 1)
        prefs.forgetServerAt("http://home:8095")

        assertThat(prefs[token("home")]).isEqualTo("home token")
        assertThat(prefs[url("home")]).isEqualTo("http://home:8095")
    }

    @Test
    fun `a refused token is forgotten only where it is still the saved one`() {
        val prefs = mutablePreferencesOf(authToken to "new token")
        prefs.putServerEntry("home", "http://home:8095", "new token", "alice", usedAtMs = 1)
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
        prefs.migrateToServerAccounts("http://home:8095")
        assertThat(prefs[token("home")]).isEqualTo("home token")
        assertThat(prefs[url("home")]).isEqualTo("http://home:8095")
        assertThat(prefs[migrated]).isTrue()

        // A later token that belongs to another server is not copied over the entry again.
        prefs[authToken] = "office token"
        prefs.remove(token("home"))
        prefs.migrateToServerAccounts("http://home:8095")
        assertThat(prefs[token("home")]).isNull()
    }

    @Test
    fun `the move marks itself done even with nothing to move`() {
        val prefs = mutablePreferencesOf()
        prefs.migrateToServerAccounts("")
        assertThat(prefs[migrated]).isTrue()
    }
}
