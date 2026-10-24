package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import net.asksakis.massdroidv2.data.sendspin.noise.NoisePairingCredentials
import net.asksakis.massdroidv2.data.sendspin.noise.PskCategory
import net.asksakis.massdroidv2.data.sendspin.noise.ResolvedPsk
import org.junit.Test

/**
 * The Noise flow after the handshake, per wire: what a re-handshake does to the roles, and how a
 * `pairing_psk` pairing is finalized. On rc1 the roles persist across a re-handshake and
 * `client/pair-init` with the pairing index precedes `client/pair-finalize` (spec `pairing.md`).
 */
class NoiseRc1FlowTest {

    private val roles = listOf("player@v1", "metadata@v1")
    private val longTermPsk = ByteArray(32) { 9 }
    private var pskCategory = PskCategory.LONG_TERM
    private var wire = SendspinWire.RC1
    private val playerCalls = mutableListOf<String>()

    private val client = mockk<SendspinClient>(relaxed = true).also {
        every { it.noisePsk() } answers { ResolvedPsk("psk", ByteArray(32), pskCategory, SERVER_ID) }
        every { it.noiseServerId() } returns SERVER_ID
        every { it.wire } answers { wire }
    }
    private val pairing = mockk<NoisePairingCredentials>(relaxed = true).also {
        every { it.beginFinalize(SERVER_ID) } returns longTermPsk
    }

    private val flow = SendspinNoiseFlow(
        client = client,
        pairing = pairing,
        player = object : SendspinNoiseFlow.Player {
            override fun sendHello(trustLevel: String) = Unit
            override fun startPlayer() { playerCalls += "start" }
            override fun stopPlayer() { playerCalls += "stop" }
            override fun resendState() { playerCalls += "resend" }
        },
        onLinkEvent = { true },
    ).also { it.reset() }

    private val pairingActivate = ServerActivatePayload(
        activities = listOf("pairing"),
        pairing = ActivatePairing(method = PAIR_METHOD_PAIRING_PSK),
    )

    @Test
    fun `on rc1 the roles persist across a re-handshake, so the player keeps going`() {
        flow.onActivate(ServerActivatePayload(activeRoles = roles))
        flow.onRehandshake()
        flow.onActivate(ServerActivatePayload(activities = emptyList()))

        assertThat(playerCalls).containsExactly("start", "resend").inOrder()
        assertThat(flow.playerRoleActive).isTrue()
    }

    @Test
    fun `on the legacy wire a re-handshake drops the roles until the server names them again`() {
        wire = SendspinWire.LEGACY

        flow.onActivate(ServerActivatePayload(activeRoles = roles))
        flow.onRehandshake()
        flow.onActivate(ServerActivatePayload(activities = emptyList()))

        assertThat(playerCalls).containsExactly("start", "stop").inOrder()
    }

    @Test
    fun `on rc1 a pairing sends pair-init with the first index before pair-finalize`() {
        pskCategory = PskCategory.PAIRING

        flow.onActivate(pairingActivate)

        verifyOrder {
            client.sendPairInit(1)
            client.sendPairFinalize(longTermPsk)
        }
    }

    @Test
    fun `the pairing index counts the pairing activations since the last handshake`() {
        pskCategory = PskCategory.PAIRING

        flow.onActivate(pairingActivate)
        flow.onActivate(pairingActivate)
        flow.onRehandshake()
        flow.onActivate(pairingActivate)

        verifyOrder {
            client.sendPairInit(1)
            client.sendPairInit(2)
            client.sendPairInit(1)
        }
    }

    @Test
    fun `on the legacy wire a pairing sends only pair-finalize`() {
        wire = SendspinWire.LEGACY
        pskCategory = PskCategory.PAIRING

        flow.onActivate(pairingActivate)

        verify(exactly = 0) { client.sendPairInit(any()) }
        verify { client.sendPairFinalize(longTermPsk) }
    }

    private companion object {
        const val SERVER_ID = "server"
    }
}
