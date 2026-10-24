package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The rc1 `client/hello` and `client/state` against the same messages built by aiosendspin 10's
 * own client models (`generate_rc1_messages.py`). The generator also checks that the server's
 * parser finds none of the legacy markers in them: `trust_level`, the list form of
 * `supported_pair_methods`, hello `supported_commands` and `static_delay_ms`. Matching them is
 * what makes the server speak the rc1 wire to the phone and stop flagging it.
 */
class Rc1WireMessagesTest {

    // The app's Json (AppModule): defaults are encoded, so a field left at its default still goes out.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val expected: JsonObject = json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("sendspin/rc1_messages.json")).readText()
    ).jsonObject

    private inline fun <reified T> encoded(message: T): JsonObject =
        json.parseToJsonElement(json.encodeToString(message)).jsonObject

    @Test
    fun `the rc1 hello is the one aiosendspin's client sends`() {
        val hello = SendspinRc1ClientHello(
            payload = Rc1ClientHelloPayload(
                name = "MassDroid",
                deviceInfo = DeviceInfo(softwareVersion = "2.40.0"),
                playerV1Support = Rc1PlayerV1Support(supportedFormats = helloSupportedFormats("flac")),
            )
        )

        assertThat(encoded(hello)).isEqualTo(expected.getValue("hello"))
    }

    @Test
    fun `the rc1 state carries the renamed delay, the commands and the preferred format`() {
        val state = SendspinRc1ClientState(
            payload = Rc1ClientStatePayload(
                available = true,
                player = Rc1PlayerState(
                    volume = 40,
                    muted = false,
                    outputDelayMs = 0,
                    minBufferMs = sendspinMinBufferMs(onCellular = true),
                    format = helloSupportedFormats("opus").first(),
                ),
            )
        )

        assertThat(encoded(state)).isEqualTo(expected.getValue("state"))
    }

    @Test
    fun `without a player role the rc1 state leaves the player out instead of sending null`() {
        val state = SendspinRc1AvailabilityState(payload = Rc1AvailabilityPayload(available = true))

        assertThat(encoded(state)).isEqualTo(expected.getValue("state_without_player"))
    }
}
