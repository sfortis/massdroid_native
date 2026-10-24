package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.sendspin.MetadataProgressPayload
import net.asksakis.massdroidv2.data.sendspin.SendspinWire
import net.asksakis.massdroidv2.data.sendspin.ServerMetadataPayload
import org.junit.Test

/**
 * Pins how a Sendspin metadata update changes what the notification shows. On the rc1 wire a field
 * the update leaves out is unset, so a radio stream after a track must not keep that track's
 * artist, album and cover. The legacy wire sends only what changed.
 */
class SendspinTrackFieldsTest {

    private val track = SendspinTrackFields(
        title = "Float On",
        artist = "Modest Mouse",
        album = "Good News for People Who Love Bad News",
        durationMs = 208_000L,
        artUrl = "https://ma.example/art/1",
    )

    // A radio station: a title and nothing else.
    private val radio = ServerMetadataPayload(title = "Radio Paradise")

    @Test
    fun `on rc1 a field the update leaves out is cleared`() {
        val shown = track.updatedBy(radio, SendspinWire.RC1)

        assertThat(shown).isEqualTo(
            SendspinTrackFields(title = "Radio Paradise", artist = "", album = "", durationMs = 0L, artUrl = null)
        )
    }

    @Test
    fun `on the legacy wire a field the update leaves out keeps its value`() {
        val shown = track.updatedBy(radio, SendspinWire.LEGACY)

        assertThat(shown).isEqualTo(track.copy(title = "Radio Paradise"))
    }

    @Test
    fun `a missing or blank title keeps the current one on both wires`() {
        val untitled = ServerMetadataPayload(title = " ", artist = "Someone")

        assertThat(track.updatedBy(untitled, SendspinWire.RC1).title).isEqualTo("Float On")
        assertThat(track.updatedBy(untitled, SendspinWire.LEGACY).title).isEqualTo("Float On")
    }

    @Test
    fun `fields the update carries replace the current ones on both wires`() {
        val next = ServerMetadataPayload(
            title = "Trillions",
            artist = "DIIV",
            album = "Frog in Boiling Water",
            artworkUrl = "https://ma.example/art/2",
            progress = MetadataProgressPayload(trackProgress = 0L, trackDuration = 241_000L),
        )
        val expected = SendspinTrackFields(
            title = "Trillions",
            artist = "DIIV",
            album = "Frog in Boiling Water",
            durationMs = 241_000L,
            artUrl = "https://ma.example/art/2",
        )

        assertThat(track.updatedBy(next, SendspinWire.RC1)).isEqualTo(expected)
        assertThat(track.updatedBy(next, SendspinWire.LEGACY)).isEqualTo(expected)
    }
}
