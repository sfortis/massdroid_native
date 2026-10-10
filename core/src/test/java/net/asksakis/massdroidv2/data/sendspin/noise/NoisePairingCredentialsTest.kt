package net.asksakis.massdroidv2.data.sendspin.noise

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoisePairingCredentialsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "sendspin_identity.json")
    private val serverId = "P4o8v2fj3SZ2dURRR9w_LyJFw4156xnKdK99R3NiN04"

    private fun credentials() = NoisePairingCredentials(SendspinIdentityStore(file))

    @Test
    fun `the pairing psk resolves as a pairing psk bound to no server`() {
        val credentials = credentials()
        val psk = credentials.newPairingPsk()

        val resolved = checkNotNull(credentials.resolve(SendspinNoise.pskId(psk)))

        assertThat(resolved.category).isEqualTo(PskCategory.PAIRING)
        assertThat(resolved.serverId).isNull()
        assertThat(resolved.psk).isEqualTo(psk)
    }

    @Test
    fun `the finalized psk resolves before the ack and is saved by the handshake that uses it`() {
        val credentials = credentials()
        credentials.newPairingPsk()
        val longTerm = credentials.beginFinalize(serverId)

        // The server re-handshakes right after its ack, possibly before the ack is handled.
        val resolved = checkNotNull(credentials.resolve(SendspinNoise.pskId(longTerm)))

        assertThat(resolved.category).isEqualTo(PskCategory.LONG_TERM)
        assertThat(resolved.serverId).isEqualTo(serverId)
        assertThat(SendspinIdentityStore(file).hasLongTermPsk(serverId)).isTrue()
    }

    @Test
    fun `the ack saves the pending psk and ends the pairing psk`() {
        val credentials = credentials()
        val pairing = credentials.newPairingPsk()
        val longTerm = credentials.beginFinalize(serverId)

        credentials.completeFinalize(serverId)

        assertThat(SendspinIdentityStore(file).resolve(SendspinNoise.pskId(longTerm))?.serverId).isEqualTo(serverId)
        assertThat(credentials.resolve(SendspinNoise.pskId(pairing))).isNull()
    }

    @Test
    fun `an abandoned attempt leaves nothing behind`() {
        val credentials = credentials()
        val pairing = credentials.newPairingPsk()
        val longTerm = credentials.beginFinalize(serverId)

        credentials.abandon()
        credentials.completeFinalize(serverId)

        assertThat(credentials.resolve(SendspinNoise.pskId(pairing))).isNull()
        assertThat(credentials.resolve(SendspinNoise.pskId(longTerm))).isNull()
        assertThat(SendspinIdentityStore(file).hasLongTermPsk(serverId)).isFalse()
    }

    @Test
    fun `forget removes a saved pairing`() {
        val credentials = credentials()
        credentials.beginFinalize(serverId)
        credentials.completeFinalize(serverId)

        credentials.forget(serverId)

        assertThat(SendspinIdentityStore(file).hasLongTermPsk(serverId)).isFalse()
    }
}
