package net.asksakis.massdroidv2.domain.nfc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A tag is written once and read on phones the writer never sees, so what goes on it has
 * to survive the round trip exactly. These cover the round trip itself and the readings
 * that must be refused rather than half understood.
 */
class NfcTagPayloadTest {

    @Test
    fun `round trips a playlist bound to a player`() {
        val payload = NfcTagPayload(
            mediaUri = "library://playlist/12",
            playerId = "ma_8f3c1a",
            label = "Kitchen morning"
        )

        val parsed = NfcTagPayload.parse(payload.toTagUri())

        assertThat(parsed).isEqualTo(payload)
    }

    @Test
    fun `keeps a colon and a slash unescaped so the payload stays small`() {
        val uri = NfcTagPayload("library://playlist/12", "ma_8f3c1a", null).toTagUri()

        assertThat(uri).isEqualTo("massdroid://play?media=library://playlist/12&player=ma_8f3c1a")
    }

    @Test
    fun `escapes what would end the value`() {
        val payload = NfcTagPayload("deezer://album/1&2=3", null, "Rock & Roll")

        val uri = payload.toTagUri()

        assertThat(uri).contains("media=deezer://album/1%262%3D3")
        assertThat(NfcTagPayload.parse(uri)).isEqualTo(payload)
    }

    @Test
    fun `keeps a plus sign in the media uri`() {
        val payload = NfcTagPayload("filesystem://track/a+b.flac", null, null)

        assertThat(NfcTagPayload.parse(payload.toTagUri())?.mediaUri)
            .isEqualTo("filesystem://track/a+b.flac")
    }

    @Test
    fun `round trips a label outside ascii`() {
        val payload = NfcTagPayload("library://album/7", null, "Μουσική")

        assertThat(NfcTagPayload.parse(payload.toTagUri())).isEqualTo(payload)
    }

    @Test
    fun `reads a tag written without a player`() {
        val parsed = NfcTagPayload.parse("massdroid://play?media=library://album/7")

        assertThat(parsed).isEqualTo(NfcTagPayload("library://album/7", null, null))
    }

    @Test
    fun `ignores parameters it does not know`() {
        val parsed = NfcTagPayload.parse("massdroid://play?media=library://album/7&shuffle=1")

        assertThat(parsed?.mediaUri).isEqualTo("library://album/7")
    }

    @Test
    fun `refuses anything that is not one of our tags`() {
        assertThat(NfcTagPayload.parse(null)).isNull()
        assertThat(NfcTagPayload.parse("")).isNull()
        assertThat(NfcTagPayload.parse("https://example.com/play?media=x")).isNull()
        // Our scheme, somebody else's instruction.
        assertThat(NfcTagPayload.parse("massdroid://auth?media=library://album/7")).isNull()
        // Nothing to play.
        assertThat(NfcTagPayload.parse("massdroid://play")).isNull()
        assertThat(NfcTagPayload.parse("massdroid://play?player=ma_8f3c1a")).isNull()
        assertThat(NfcTagPayload.parse("massdroid://play?media=")).isNull()
    }
}
