package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.sendspin.noise.PskCategory
import org.junit.Test

/**
 * Pins what the phone does on each `server/activate`, and the shape of its Noise hello. The
 * activation rules follow aiosendspin's client; the hello is what MA's pair_web_player route
 * needs to see (pairing_psk offered, unpaired access advertised, no client_id).
 */
class NoiseActivationTest {

    private val roles = listOf("player@v1", "metadata@v1")

    private fun decide(
        payload: ServerActivatePayload,
        psk: PskCategory = PskCategory.LONG_TERM,
        effectiveRoles: List<String> = payload.activeRoles.orEmpty(),
        playerActive: Boolean = false,
        resync: Boolean = false,
        wire: SendspinWire = SendspinWire.LEGACY,
    ) = NoiseActivationStep.decide(payload, psk, effectiveRoles, playerActive, resync, wire)

    private val pairingPsk = ActivatePairing(method = PAIR_METHOD_PAIRING_PSK)
    private val unauthorized = NoiseActivationStep.Reject(GOODBYE_REASON_UNAUTHORIZED)

    @Test
    fun `on rc1 a pairing alongside playback is finalized, where the legacy wire refused it`() {
        // aiosendspin 10 keeps an approved phone playing while it pairs and sends both activities.
        val payload = ServerActivatePayload(activities = listOf("playback", "pairing"), pairing = pairingPsk)

        assertThat(decide(payload, psk = PskCategory.PAIRING, effectiveRoles = roles, playerActive = true, wire = SendspinWire.RC1))
            .isEqualTo(NoiseActivationStep.FinalizePairing)
        assertThat(decide(payload, psk = PskCategory.PAIRING, effectiveRoles = roles, playerActive = true))
            .isEqualTo(unauthorized)
    }

    @Test
    fun `on rc1 management is not an activity, so it is refused even on a long-term psk`() {
        val payload = ServerActivatePayload(activities = listOf("management"))

        assertThat(decide(payload, wire = SendspinWire.RC1)).isEqualTo(unauthorized)
        assertThat(decide(payload)).isNotEqualTo(unauthorized)
    }

    @Test
    fun `on rc1 a long-term psk admits no pairing`() {
        val payload = ServerActivatePayload(activities = listOf("pairing"), pairing = pairingPsk)

        assertThat(decide(payload, wire = SendspinWire.RC1)).isEqualTo(unauthorized)
    }

    @Test
    fun `on rc1 a player role on the pairing psk is admitted, because unpaired access is on`() {
        val payload = ServerActivatePayload(activities = emptyList(), activeRoles = roles)

        assertThat(decide(payload, psk = PskCategory.PAIRING, wire = SendspinWire.RC1)).isEqualTo(NoiseActivationStep.StartPlayer)
    }

    @Test
    fun `on rc1 an unpaired phone the operator approved plays`() {
        val payload = ServerActivatePayload(activities = listOf("playback"), activeRoles = roles)

        assertThat(decide(payload, psk = PskCategory.SENTINEL, wire = SendspinWire.RC1)).isEqualTo(NoiseActivationStep.StartPlayer)
    }

    @Test
    fun `on rc1 a pairing_psk pairing on another psk is aborted`() {
        val payload = ServerActivatePayload(activities = listOf("pairing"), pairing = pairingPsk)

        assertThat(decide(payload, psk = PskCategory.SENTINEL, wire = SendspinWire.RC1))
            .isEqualTo(NoiseActivationStep.AbortPairing(PAIR_ABORT_METHOD_NOT_SUPPORTED))
    }

    @Test
    fun `named roles replace the previous ones on either wire`() {
        val payload = ServerActivatePayload(activities = emptyList(), activeRoles = listOf("metadata@v1"))

        for (wire in SendspinWire.entries) {
            assertThat(NoiseActivationStep.effectiveRoles(payload, PskCategory.LONG_TERM, roles, wire))
                .containsExactly("metadata@v1")
        }
    }

    @Test
    fun `omitted roles persist while the connection can still play`() {
        val payload = ServerActivatePayload(activities = emptyList())

        assertThat(NoiseActivationStep.effectiveRoles(payload, PskCategory.LONG_TERM, roles, SendspinWire.RC1))
            .isEqualTo(roles)
        assertThat(NoiseActivationStep.effectiveRoles(payload, PskCategory.LONG_TERM, roles, SendspinWire.LEGACY))
            .isEqualTo(roles)
    }

    @Test
    fun `on rc1 omitted roles empty once the connection cannot play`() {
        // A long-term PSK with pairing is not playback-capable on rc1; the persisted roles go.
        val payload = ServerActivatePayload(activities = listOf("pairing"), pairing = pairingPsk)

        assertThat(NoiseActivationStep.effectiveRoles(payload, PskCategory.LONG_TERM, roles, SendspinWire.RC1)).isEmpty()
    }

    @Test
    fun `a paired idle phone starts its player although nothing is playing`() {
        val step = decide(ServerActivatePayload(activities = emptyList(), activeRoles = roles))

        assertThat(step).isEqualTo(NoiseActivationStep.StartPlayer)
    }

    @Test
    fun `playback toggling on an active player changes nothing`() {
        val step = decide(ServerActivatePayload(activities = listOf("playback")), effectiveRoles = roles, playerActive = true)

        assertThat(step).isEqualTo(NoiseActivationStep.None)
    }

    @Test
    fun `after a re-handshake an active player reports its state again`() {
        val step = decide(ServerActivatePayload(activeRoles = roles), playerActive = true, resync = true)

        assertThat(step).isEqualTo(NoiseActivationStep.ResendState)
    }

    @Test
    fun `an unpaired phone without roles waits`() {
        val step = decide(ServerActivatePayload(activities = emptyList(), activeRoles = emptyList()), psk = PskCategory.SENTINEL)

        assertThat(step).isEqualTo(NoiseActivationStep.None)
    }

    @Test
    fun `withdrawn roles stop the player`() {
        val step = decide(ServerActivatePayload(activeRoles = emptyList()), playerActive = true)

        assertThat(step).isEqualTo(NoiseActivationStep.StopPlayer)
    }

    @Test
    fun `a pairing_psk pairing on the pairing psk is finalized`() {
        val payload = ServerActivatePayload(activities = listOf("pairing"), pairing = ActivatePairing("pairing_psk"))

        assertThat(decide(payload, psk = PskCategory.PAIRING)).isEqualTo(NoiseActivationStep.FinalizePairing)
    }

    @Test
    fun `a pin pairing, or pairing_psk on another psk, is aborted`() {
        val pin = ServerActivatePayload(activities = listOf("pairing"), pairing = ActivatePairing("dynamic_pin"))
        val psk = ServerActivatePayload(activities = listOf("pairing"), pairing = ActivatePairing("pairing_psk"))
        val abort = NoiseActivationStep.AbortPairing(PAIR_ABORT_METHOD_NOT_SUPPORTED)

        assertThat(decide(pin, psk = PskCategory.PAIRING)).isEqualTo(abort)
        assertThat(decide(psk, psk = PskCategory.SENTINEL)).isEqualTo(abort)
    }

    @Test
    fun `a player role on the pairing psk is refused`() {
        val step = decide(ServerActivatePayload(activities = emptyList(), activeRoles = roles), psk = PskCategory.PAIRING)

        assertThat(step).isEqualTo(NoiseActivationStep.Reject(GOODBYE_REASON_UNAUTHORIZED))
    }

    @Test
    fun `pairing mixed with another activity is refused`() {
        val payload = ServerActivatePayload(activities = listOf("pairing", "playback"), pairing = ActivatePairing("pairing_psk"))

        assertThat(decide(payload, psk = PskCategory.PAIRING)).isEqualTo(NoiseActivationStep.Reject(GOODBYE_REASON_UNAUTHORIZED))
    }

    @Test
    fun `an unpaired phone the operator approved plays`() {
        val step = decide(ServerActivatePayload(activities = listOf("playback"), activeRoles = roles), psk = PskCategory.SENTINEL)

        assertThat(step).isEqualTo(NoiseActivationStep.StartPlayer)
    }

    @Test
    fun `management on the sentinel psk is refused`() {
        val step = decide(ServerActivatePayload(activities = listOf("management")), psk = PskCategory.SENTINEL)

        assertThat(step).isEqualTo(NoiseActivationStep.Reject(GOODBYE_REASON_UNAUTHORIZED))
    }

    @Test
    fun `server activate and the pairing messages parse`() {
        val json = Json { ignoreUnknownKeys = true }
        val activate = SendspinIncoming.parse(
            """{"type":"server/activate","payload":{"activities":["pairing"],"pairing":{"method":"pairing_psk"}}}""",
            json,
        ) as SendspinIncoming.ServerActivate

        assertThat(activate.payload.activities).containsExactly("pairing")
        assertThat(activate.payload.activeRoles).isNull()
        assertThat(activate.payload.pairing?.method).isEqualTo("pairing_psk")
        assertThat(SendspinIncoming.parse("""{"type":"server/pair-finalize","payload":{}}""", json))
            .isEqualTo(SendspinIncoming.ServerPairFinalize)
        assertThat(SendspinIncoming.parse("""{"type":"server/unpair","payload":{}}""", json))
            .isEqualTo(SendspinIncoming.ServerUnpair)
        assertThat(SendspinIncoming.parse("""{"type":"pair/abort","payload":{"reason":"attempt_timeout"}}""", json))
            .isEqualTo(SendspinIncoming.PairAbort("attempt_timeout"))
    }

    @Test
    fun `the Noise hello leaves out client_id and version and offers pairing_psk`() {
        // The app's Json encodes defaults, so every field below is on the wire.
        val json = Json { encodeDefaults = true }
        val hello = json.encodeToString(
            SendspinNoiseClientHello.serializer(),
            SendspinNoiseClientHello(payload = NoiseClientHelloPayload(name = "Phone", trustLevel = TRUST_LEVEL_NONE)),
        )
        val payload = Json.parseToJsonElement(hello).jsonObject.getValue("payload").jsonObject

        assertThat(payload.keys).containsNoneOf("client_id", "version")
        assertThat(payload.getValue("trust_level").jsonPrimitive.content).isEqualTo("none")
        assertThat(payload.getValue("supported_pair_methods").jsonArray.map { it.jsonObject.getValue("method").jsonPrimitive.content })
            .containsExactly("pairing_psk")
        assertThat(payload.getValue("unpaired_access").jsonObject.getValue("enabled").jsonPrimitive.content).isEqualTo("true")
        assertThat(payload.getValue("device_info").jsonObject.getValue("product_name").jsonPrimitive.content)
            .isEqualTo("Mobile Application")
        assertThat(payload.keys).contains("player@v1_support")
    }

    @Test
    fun `only a player family role counts as a player role`() {
        assertThat(NoiseActivationStep.hasPlayerRole(listOf("player@v1"))).isTrue()
        assertThat(NoiseActivationStep.hasPlayerRole(listOf("metadata@v1", "player@v2"))).isTrue()
        assertThat(NoiseActivationStep.hasPlayerRole(emptyList())).isFalse()
        assertThat(NoiseActivationStep.hasPlayerRole(listOf("metadata@v1", "controller@v1"))).isFalse()
    }
}
