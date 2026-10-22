package net.asksakis.massdroidv2.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the split between the backed-up settings file and the device-only file.
 *
 * The settings file goes into Android backup and device transfer. Anything left in it after the
 * move would be restored on another device: the MA password in plain text, or this phone's
 * Sendspin identity, which would make a second phone show up as the first one's player.
 */
class DeviceKeysMigrationTest {

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var settings: DataStore<Preferences>

    private val serverUrl = stringPreferencesKey("server_url")
    private val authToken = stringPreferencesKey("auth_token")
    private val password = stringPreferencesKey("password")
    private val sendspinClientId = stringPreferencesKey("sendspin_client_id")

    @Before
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("device-keys").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun deviceStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(MoveDeviceKeysMigration(settings)),
        scope = scope,
    ) { File(dir, "device.preferences_pb") }

    @Test
    fun `credentials and identity move to the device file and leave the settings file`() = runBlocking {
        settings.edit {
            it[serverUrl] = "https://ma.example"
            it[authToken] = "token"
            it[password] = "secret"
            it[sendspinClientId] = "phone-id"
        }

        val device = deviceStore().data.first()
        val remaining = settings.data.first()

        assertThat(device[authToken]).isEqualTo("token")
        assertThat(device[password]).isEqualTo("secret")
        assertThat(device[sendspinClientId]).isEqualTo("phone-id")
        assertThat(remaining[authToken]).isNull()
        assertThat(remaining[password]).isNull()
        assertThat(remaining[sendspinClientId]).isNull()
        // The server address is a setting the listener wants back after a restore.
        assertThat(remaining[serverUrl]).isEqualTo("https://ma.example")
    }

    @Test
    fun `every device key is left out of the settings file`() = runBlocking {
        settings.edit { prefs ->
            // Only the key names matter here; DataStore stores whatever value it is given.
            DEVICE_KEYS.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                prefs[key as Preferences.Key<String>] = "x"
            }
        }
        deviceStore().data.first()

        val remaining = settings.data.first()
        DEVICE_KEYS.forEach { key -> assertThat(remaining.contains(key)).isFalse() }
    }

    @Test
    fun `a key written after the move is not overwritten by a stale copy`() = runBlocking {
        settings.edit { it[authToken] = "old" }
        val device = deviceStore()
        device.data.first()
        device.edit { it[authToken] = "new" }
        // A stale value reappearing in the settings file, for example from an old backup.
        settings.edit { it[authToken] = "old" }

        assertThat(device.data.first()[authToken]).isEqualTo("new")
    }
}
