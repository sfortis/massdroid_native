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
            action = NfcTagAction.PlayMedia("library://playlist/12"),
            playerId = "ma_8f3c1a",
            label = "Kitchen morning"
        )

        val parsed = NfcTagPayload.parse(payload.toTagUri())

        assertThat(parsed).isEqualTo(payload)
    }

    @Test
    fun `keeps a colon and a slash unescaped so the payload stays small`() {
        val uri = NfcTagPayload(NfcTagAction.PlayMedia("library://playlist/12"), "ma_8f3c1a", null).toTagUri()

        assertThat(uri).isEqualTo("massdroid://play?media=library://playlist/12&player=ma_8f3c1a")
    }

    @Test
    fun `escapes what would end the value`() {
        val payload = NfcTagPayload(NfcTagAction.PlayMedia("deezer://album/1&2=3"), null, "Rock & Roll")

        val uri = payload.toTagUri()

        assertThat(uri).contains("media=deezer://album/1%262%3D3")
        assertThat(NfcTagPayload.parse(uri)).isEqualTo(payload)
    }

    @Test
    fun `keeps a plus sign in the media uri`() {
        val payload = NfcTagPayload(NfcTagAction.PlayMedia("filesystem://track/a+b.flac"), null, null)

        assertThat(NfcTagPayload.parse(payload.toTagUri())?.mediaUri)
            .isEqualTo("filesystem://track/a+b.flac")
    }

    @Test
    fun `round trips a label outside ascii`() {
        val payload = NfcTagPayload(NfcTagAction.PlayMedia("library://album/7"), null, "Μουσική")

        assertThat(NfcTagPayload.parse(payload.toTagUri())).isEqualTo(payload)
    }

    @Test
    fun `reads a tag written without a player`() {
        val parsed = NfcTagPayload.parse("massdroid://play?media=library://album/7")

        assertThat(parsed).isEqualTo(NfcTagPayload(NfcTagAction.PlayMedia("library://album/7"), null, null))
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

    @Test
    fun `round trips a tag that only names a speaker`() {
        for (action in listOf(NfcTagAction.TransferQueue, NfcTagAction.Resume)) {
            val payload = NfcTagPayload(action, "ma_8f3c1a", "Kitchen")

            assertThat(NfcTagPayload.parse(payload.toTagUri())).isEqualTo(payload)
        }
    }

    @Test
    fun `a speaker action without a speaker names nothing and is refused`() {
        assertThat(NfcTagPayload.parse("massdroid://play?action=transfer")).isNull()
        assertThat(NfcTagPayload.parse("massdroid://play?action=resume")).isNull()
    }

    @Test
    fun `an action this version does not know is refused rather than guessed at`() {
        assertThat(NfcTagPayload.parse("massdroid://play?action=explode&player=ma_8f3c1a")).isNull()
    }

    @Test
    fun `a media tag still reads as media even when an action rides along`() {
        val parsed = NfcTagPayload.parse(
            "massdroid://play?media=library://album/7&action=transfer&player=ma_8f3c1a"
        )

        assertThat(parsed?.action).isEqualTo(NfcTagAction.PlayMedia("library://album/7"))
    }

    @Test
    fun `round trips a level alongside the rest`() {
        val payload = NfcTagPayload(
            action = NfcTagAction.PlayMedia("library://playlist/12"),
            playerId = "ma_8f3c1a",
            label = "Kitchen morning",
            volume = 35
        )

        assertThat(NfcTagPayload.parse(payload.toTagUri())).isEqualTo(payload)
    }

    @Test
    fun `a tag with no level says nothing about volume`() {
        val parsed = NfcTagPayload.parse("massdroid://play?media=library://album/7")

        assertThat(parsed?.volume).isNull()
    }

    @Test
    fun `a level that is not on the scale is ignored rather than clamped`() {
        // Leaving the volume where the listener put it beats acting on nonsense.
        for (bad in listOf("101", "-1", "loud", "")) {
            val parsed = NfcTagPayload.parse("massdroid://play?media=library://album/7&vol=$bad")

            assertThat(parsed?.volume).isNull()
        }
    }

    @Test
    fun `silence is a level like any other`() {
        val parsed = NfcTagPayload.parse("massdroid://play?media=library://album/7&vol=0")

        assertThat(parsed?.volume).isEqualTo(0)
    }
}
