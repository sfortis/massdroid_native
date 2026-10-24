package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import net.asksakis.massdroidv2.data.sendspin.noise.NoisePairingCredentials
import net.asksakis.massdroidv2.data.sendspin.noise.PskCategory
import net.asksakis.massdroidv2.data.sendspin.noise.ResolvedPsk
import org.junit.Test

/**
 * Pins when a Noise connection holds back its `client/state` for a pairing.
 *
 * aiosendspin drops a connection's roles without a message before it re-handshakes for a pairing,
 * and MA approves guest access on the same unpaired connection at about the same time. A state
 * sent in that window names a player role the server has just dropped, which MA logs as a
 * non-compliant client (2026-10-12, on the phone and on the TV). The hold covers the window, from
 * the first unpaired activate to the activate after the pairing's re-handshake, and must never
 * stay stuck: a failed attempt, a supervisor that holds pairing back and an undelivered link event
 * each end it.
 */
class NoisePairingHoldTest {

    private val roles = listOf("player@v1", "metadata@v1")
    private var pskCategory = PskCategory.SENTINEL
    private var linkEventsDelivered = true
    private var wire = SendspinWire.LEGACY
    private val linkEvents = mutableListOf<NoiseLinkEvent>()
    private val heldWhen = mutableListOf<Pair<String, Boolean>>()

    private val client = mockk<SendspinClient>(relaxed = true).also {
        every { it.noisePsk() } answers { ResolvedPsk("psk", ByteArray(32), pskCategory, SERVER_ID) }
        every { it.noiseServerId() } returns SERVER_ID
        // The hold exists for the legacy wire's quiesce; the rc1 tests set the wire themselves.
        every { it.wire } answers { wire }
    }

    private lateinit var flow: SendspinNoiseFlow

    init {
        flow = SendspinNoiseFlow(
            client = client,
            pairing = mockk<NoisePairingCredentials>(relaxed = true),
            player = object : SendspinNoiseFlow.Player {
                override fun sendHello(trustLevel: String) = Unit
                override fun startPlayer() { heldWhen += "start" to flow.stateHeldForPairing }
                override fun stopPlayer() = Unit
                override fun resendState() { heldWhen += "resend" to flow.stateHeldForPairing }
            },
            onLinkEvent = { event -> linkEvents += event; linkEventsDelivered },
        )
        flow.reset()
    }

    private fun activate() = flow.onActivate(ServerActivatePayload(activeRoles = roles))

    @Test
    fun `the first unpaired activate holds the state before the player starts`() {
        activate()

        assertThat(heldWhen).containsExactly("start" to true)
        assertThat(linkEvents).containsExactly(NoiseLinkEvent.Unpaired(SERVER_ID))
        assertThat(flow.stateHeldForPairing).isTrue()
    }

    @Test
    fun `on the rc1 wire an unpaired connection sends its state at once`() {
        wire = SendspinWire.RC1

        activate()
        flow.onPairingRequested()

        assertThat(heldWhen).containsExactly("start" to false)
        assertThat(linkEvents).containsExactly(NoiseLinkEvent.Unpaired(SERVER_ID))
        assertThat(flow.stateHeldForPairing).isFalse()
    }

    @Test
    fun `a paired connection never holds its state`() {
        pskCategory = PskCategory.LONG_TERM

        activate()

        assertThat(heldWhen).containsExactly("start" to false)
        assertThat(flow.stateHeldForPairing).isFalse()
    }

    @Test
    fun `after the re-handshake the hold lasts until the next activate`() {
        activate()
        val epoch = flow.onPairingRequested()
        flow.onRehandshake()

        // The pairing request returns after the re-handshake: the roles are still unknown.
        assertThat(flow.onPairingAttemptEnded(epoch)).isFalse()
        assertThat(flow.stateHeldForPairing).isTrue()

        pskCategory = PskCategory.LONG_TERM
        activate()

        assertThat(heldWhen.last()).isEqualTo("resend" to false)
        assertThat(flow.stateHeldForPairing).isFalse()
    }

    @Test
    fun `a failed attempt ends the hold once`() {
        activate()
        val epoch = flow.onPairingRequested()

        assertThat(flow.onPairingAttemptEnded(epoch)).isTrue()
        assertThat(flow.stateHeldForPairing).isFalse()
        assertThat(flow.onPairingAttemptEnded(epoch)).isFalse()
    }

    @Test
    fun `a retry after a failed attempt holds the state again`() {
        activate()
        flow.onPairingAttemptEnded(flow.onPairingRequested())

        flow.onPairingRequested()

        assertThat(flow.stateHeldForPairing).isTrue()
    }

    @Test
    fun `an attempt from an earlier connection does not end the current hold`() {
        activate()
        val staleEpoch = flow.onPairingRequested()
        flow.reset()
        activate()

        assertThat(flow.onPairingAttemptEnded(staleEpoch)).isFalse()
        assertThat(flow.stateHeldForPairing).isTrue()
    }

    @Test
    fun `a supervisor that holds pairing back ends the hold`() {
        activate()

        assertThat(flow.onPairingHeldBack()).isTrue()
        assertThat(flow.stateHeldForPairing).isFalse()
        assertThat(flow.onPairingHeldBack()).isFalse()
    }

    @Test
    fun `an undelivered unpaired event ends the hold and sends the state`() {
        linkEventsDelivered = false

        activate()

        assertThat(flow.stateHeldForPairing).isFalse()
        assertThat(heldWhen).containsExactly("start" to true, "resend" to false).inOrder()
    }

    @Test
    fun `a new connection starts without a hold`() {
        activate()

        flow.reset()

        assertThat(flow.stateHeldForPairing).isFalse()
    }

    private companion object {
        const val SERVER_ID = "server-1"
    }
}
