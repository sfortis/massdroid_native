package net.asksakis.massdroidv2.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The servers the first library sync after the update starts from: every server with a saved
 * account, and every server the speaker registered on. Without them, a first sync on another
 * server than the one used so far would read the database as that server's alone.
 */
class KnownServerIdsTest {

    @Test
    fun `servers with a saved account and servers the speaker registered on are known`() {
        val device = mutablePreferencesOf(
            stringPreferencesKey("auth_token@home") to "t1",
            stringPreferencesKey("auth_token@test") to "t2",
            stringPreferencesKey("auth_token") to "current",
            stringPreferencesKey("trusted_server_id") to "home",
            stringSetPreferencesKey("sendspin_migrated_servers") to setOf("old")
        )

        assertThat(knownServerIds(device)).containsExactly("home", "test", "old")
    }

    @Test
    fun `a device store without accounts knows no server`() {
        assertThat(knownServerIds(mutablePreferencesOf())).isEmpty()
    }
}
