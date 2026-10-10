package net.asksakis.massdroidv2.data.sendspin.noise

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `the finalize psk is saved before it is sent, so it survives the app dying before the ack`() {
        val longTerm = credentials().beginFinalize(serverId)

        // A new process: only the file is left.
        val resolved = checkNotNull(credentials().resolve(SendspinNoise.pskId(longTerm)))

        assertThat(resolved.category).isEqualTo(PskCategory.LONG_TERM)
        assertThat(resolved.serverId).isEqualTo(serverId)
    }

    @Test
    fun `the ack ends the pairing psk`() {
        val credentials = credentials()
        val pairing = credentials.newPairingPsk()
        credentials.beginFinalize(serverId)

        credentials.completeFinalize(serverId)

        assertThat(credentials.resolve(SendspinNoise.pskId(pairing))).isNull()
    }

    @Test
    fun `an abandoned attempt drops the pairing psk`() {
        val credentials = credentials()
        val pairing = credentials.newPairingPsk()

        credentials.abandon()

        assertThat(credentials.resolve(SendspinNoise.pskId(pairing))).isNull()
    }

    @Test
    fun `forget removes a saved pairing`() {
        val credentials = credentials()
        credentials.beginFinalize(serverId)

        credentials.forget(serverId)

        assertThat(SendspinIdentityStore(file).hasLongTermPsk(serverId)).isFalse()
    }

    @Test
    fun `the pairing token is byte for byte the server's encoding`() {
        val vectors = Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResource("sendspin/noise_vectors.json")).readText()
        ).jsonObject
        fun bytes(key: String) = SendspinNoise.b64urlDecode(vectors.getValue(key).jsonPrimitive.content)

        val token = SendspinPairingToken.encode(bytes("client_static_public"), bytes("pairing_psk"))

        assertThat(token).isEqualTo(vectors.getValue("pairing_token").jsonPrimitive.content)
    }
}
