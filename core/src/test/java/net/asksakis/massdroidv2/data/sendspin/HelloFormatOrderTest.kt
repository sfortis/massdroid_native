package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import org.junit.Test

/**
 * Pins the Automatic format choice. With the player's server format on
 * "automatic", the server streams the first format of our hello (and a later
 * `stream/request-format`), so this rule is the whole decision: Opus only for a
 * solo player on mobile data, FLAC otherwise.
 *
 * A wrong answer fails silently. Opus in a sync group costs the server about six
 * times the encode work of FLAC, and on a loaded host that work stalled the group
 * timeline after every seek (measured 2026-10-02). FLAC solo on mobile data
 * streams about ten times the bitrate over a weak link.
 */
class HelloFormatOrderTest {

    @Test
    fun `solo on Wi-Fi wants FLAC`() {
        assertThat(preferredSendspinCodec(inSyncGroup = false, onCellular = false)).isEqualTo("flac")
    }

    @Test
    fun `solo on mobile data wants Opus`() {
        assertThat(preferredSendspinCodec(inSyncGroup = false, onCellular = true)).isEqualTo("opus")
    }

    @Test
    fun `a sync group wants FLAC on any network`() {
        assertThat(preferredSendspinCodec(inSyncGroup = true, onCellular = false)).isEqualTo("flac")
        assertThat(preferredSendspinCodec(inSyncGroup = true, onCellular = true)).isEqualTo("flac")
    }

    @Test
    fun `the hello puts the wanted codec first and keeps the others`() {
        assertThat(helloSupportedFormats("flac").map { it.codec }).containsExactly("flac", "opus", "pcm").inOrder()
        assertThat(helloSupportedFormats("opus").map { it.codec }).containsExactly("opus", "flac", "pcm").inOrder()
    }

    @Test
    fun `every announced format is 48 kHz 16 bit stereo`() {
        val all = helloSupportedFormats("flac") + helloSupportedFormats("opus")
        all.forEach {
            assertThat(it.sampleRate).isEqualTo(48_000)
            assertThat(it.bitDepth).isEqualTo(16)
            assertThat(it.channels).isEqualTo(2)
        }
    }

    @Test
    fun `a stored legacy Smart reads as Automatic`() {
        assertThat(SendspinAudioFormat.fromStored(SendspinAudioFormat.LEGACY_SMART))
            .isEqualTo(SendspinAudioFormat.AUTOMATIC)
    }

    @Test
    fun `Automatic leaves the server on automatic`() {
        assertThat(SendspinAudioFormat.AUTOMATIC.serverValue).isEqualTo("automatic")
    }
}
