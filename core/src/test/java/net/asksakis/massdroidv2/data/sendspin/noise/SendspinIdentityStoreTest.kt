package net.asksakis.massdroidv2.data.sendspin.noise

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SendspinIdentityStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "sendspin_identity.json")
    private val serverId = "P4o8v2fj3SZ2dURRR9w_LyJFw4156xnKdK99R3NiN04"
    private val psk = ByteArray(SendspinNoise.PSK_SIZE) { it.toByte() }

    @Test
    fun `the identity survives a restart`() {
        val first = SendspinIdentityStore(file).identity()
        val again = SendspinIdentityStore(file).identity()

        assertThat(again.peerId).isEqualTo(first.peerId)
        assertThat(again.peerId).hasLength(SendspinNoise.PEER_ID_LENGTH)
    }

    @Test
    fun `a saved long-term psk resolves by its id after a restart, bound to its server`() {
        SendspinIdentityStore(file).saveLongTermPsk(serverId, psk)

        val store = SendspinIdentityStore(file)
        val resolved = checkNotNull(store.resolve(SendspinNoise.pskId(psk)))

        assertThat(resolved.category).isEqualTo(PskCategory.LONG_TERM)
        assertThat(resolved.serverId).isEqualTo(serverId)
        assertThat(resolved.psk).isEqualTo(psk)
        assertThat(store.hasLongTermPsk(serverId)).isTrue()
        assertThat(store.resolve(SendspinNoise.pskId(SendspinNoise.SENTINEL_PSK))).isNull()
    }

    @Test
    fun `a removed psk no longer resolves and the identity stays`() {
        val store = SendspinIdentityStore(file)
        val peerId = store.identity().peerId
        store.saveLongTermPsk(serverId, psk)

        store.removeLongTermPsk(serverId)

        val reloaded = SendspinIdentityStore(file)
        assertThat(reloaded.resolve(SendspinNoise.pskId(psk))).isNull()
        assertThat(reloaded.hasLongTermPsk(serverId)).isFalse()
        assertThat(reloaded.identity().peerId).isEqualTo(peerId)
    }

    @Test
    fun `an unreadable file is kept aside and replaced by a new identity`() {
        file.writeText("{ not json")

        val identity = SendspinIdentityStore(file).identity()

        assertThat(File(folder.root, "sendspin_identity.json.corrupt").readText()).isEqualTo("{ not json")
        assertThat(SendspinIdentityStore(file).identity().peerId).isEqualTo(identity.peerId)
    }

    @Test
    fun `only the latest unreadable file is kept aside`() {
        File(folder.root, "sendspin_identity.json.corrupt").writeText("older copy")
        file.writeText("{ not json")

        SendspinIdentityStore(file).identity()

        assertThat(File(folder.root, "sendspin_identity.json.corrupt").readText()).isEqualTo("{ not json")
    }
}
