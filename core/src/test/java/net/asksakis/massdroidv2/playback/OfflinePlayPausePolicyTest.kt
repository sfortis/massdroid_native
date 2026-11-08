package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * With MA down, the play/pause button of the phone's own player follows the phone's
 * output, and only a pause of that output may go through.
 */
class OfflinePlayPausePolicyTest {

    private fun button(
        maConnected: Boolean = false,
        serverPlaying: Boolean = true,
        selectedPlayerId: String? = LOCAL,
        streamActive: Boolean = true,
        outputIdle: Boolean = false,
    ) = OfflinePlayPausePolicy.button(
        maConnected = maConnected,
        serverPlaying = serverPlaying,
        localPlayerId = LOCAL,
        selectedPlayerId = selectedPlayerId,
        streamActive = streamActive,
        outputIdle = outputIdle,
    )

    @Test
    fun `the phone playing its buffer with MA down may be paused`() {
        assertThat(button()).isEqualTo(OfflinePlayPausePolicy.Button(shownPlaying = true, pausableOffline = true))
    }

    /** The stale server state kept the button on Pause after the local pause. */
    @Test
    fun `after a local pause the button shows play and stays blocked`() {
        assertThat(button(outputIdle = true))
            .isEqualTo(OfflinePlayPausePolicy.Button(shownPlaying = false, pausableOffline = false))
    }

    @Test
    fun `a phone with no stream shows play with MA down whatever the server said last`() {
        assertThat(button(streamActive = false, serverPlaying = true))
            .isEqualTo(OfflinePlayPausePolicy.Button(shownPlaying = false, pausableOffline = false))
    }

    /** Another speaker cannot be reached without MA, so nothing about it changes. */
    @Test
    fun `another selected player keeps the server state and stays blocked`() {
        assertThat(button(selectedPlayerId = "other"))
            .isEqualTo(OfflinePlayPausePolicy.Button(shownPlaying = true, pausableOffline = false))
    }

    @Test
    fun `no selection keeps the server state and stays blocked`() {
        assertThat(button(selectedPlayerId = null, serverPlaying = false))
            .isEqualTo(OfflinePlayPausePolicy.Button(shownPlaying = false, pausableOffline = false))
    }

    /** With MA up the server state is current and every tap goes through as before. */
    @Test
    fun `with MA connected the button follows the server and needs no bypass`() {
        assertThat(button(maConnected = true, serverPlaying = false, outputIdle = false))
            .isEqualTo(OfflinePlayPausePolicy.Button(shownPlaying = false, pausableOffline = false))
        assertThat(button(maConnected = true, serverPlaying = true, outputIdle = true))
            .isEqualTo(OfflinePlayPausePolicy.Button(shownPlaying = true, pausableOffline = false))
    }

    private companion object {
        const val LOCAL = "local"
    }
}
