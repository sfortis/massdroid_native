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
    ) = NoiseActivationStep.decide(payload, psk, effectiveRoles, playerActive, resync)

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
