package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Pins the wire shape of `client/state` against what aiosendspin 9 checks.
 *
 * The server flagged this client as non-compliant for two reasons: it sent the
 * legacy top-level `state` instead of `available`, and its first state omitted
 * the player timing fields. Without them MA 2.10.6 assumed a 1000 ms minimum
 * buffer for the phone, and every group it joined started that much later.
 */
class ClientStateWireTest {

    // The app's Json encodes defaults (AppModule), so a field left at its default still goes out.
    private val json = Json { encodeDefaults = true }

    private fun encode(available: Boolean, onCellular: Boolean) = json.parseToJsonElement(
        json.encodeToString(
            SendspinClientState.serializer(),
            SendspinClientState(
                payload = ClientStatePayload(
                    available = available,
                    player = PlayerStateInfo(
                        volume = 40,
                        muted = false,
                        staticDelayMs = 0,
                        requiredLeadTimeMs = SENDSPIN_REQUIRED_LEAD_TIME_MS,
                        minBufferMs = sendspinMinBufferMs(onCellular)
                    )
                )
            )
        )
    ).jsonObject

    @Test
    fun `availability is the top-level available flag and the legacy state is gone`() {
        val message = encode(available = true, onCellular = false)
        val payload = message.getValue("payload").jsonObject

        assertThat(message.getValue("type").jsonPrimitive.content).isEqualTo("client/state")
        assertThat(payload.getValue("available").jsonPrimitive.boolean).isTrue()
        assertThat(payload).doesNotContainKey("state")
        assertThat(payload.getValue("player").jsonObject).doesNotContainKey("state")
    }

    @Test
    fun `the player carries all three timing fields`() {
        val player = encode(available = true, onCellular = false)
            .getValue("payload").jsonObject.getValue("player").jsonObject

        assertThat(player.getValue("static_delay_ms").jsonPrimitive.int).isEqualTo(0)
        assertThat(player.getValue("required_lead_time_ms").jsonPrimitive.int).isEqualTo(250)
        assertThat(player.getValue("min_buffer_ms").jsonPrimitive.int).isEqualTo(250)
    }

    @Test
    fun `mobile data asks for a deeper minimum buffer`() {
        val player = encode(available = true, onCellular = true)
            .getValue("payload").jsonObject.getValue("player").jsonObject

        assertThat(player.getValue("min_buffer_ms").jsonPrimitive.int).isEqualTo(1000)
    }
}
