package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The phone's output counts as playing while its buffer plays through a dropped
 * connection, and only then may a missing selection be read as unchanged.
 */
class LocalPlayingPolicyTest {

    private fun playing(
        intent: Boolean = true,
        localPlayerId: String? = LOCAL,
        selectedPlayerId: String? = LOCAL,
        transportStreaming: Boolean = true,
        streamActive: Boolean = true,
        outputSynced: Boolean = true,
        serverSpeedPositive: Boolean = true,
    ) = LocalPlayingPolicy.playing(
        intent = intent,
        localPlayerId = localPlayerId,
        selectedPlayerId = selectedPlayerId,
        transportStreaming = transportStreaming,
        streamActive = streamActive,
        outputSynced = outputSynced,
        serverSpeedPositive = serverSpeedPositive,
    )

    @Test
    fun `a streaming selected player that the listener wants is playing`() {
        assertThat(playing()).isTrue()
    }

    /** The case this exists for: the TV showed paused for the whole drop. */
    @Test
    fun `a stream held through a dropped connection is playing`() {
        assertThat(playing(transportStreaming = false, streamActive = true)).isTrue()
    }

    /** Once the buffered audio has played out, the hold ends and so does playing. */
    @Test
    fun `a dropped connection with no stream held is not playing`() {
        assertThat(playing(transportStreaming = false, streamActive = false)).isFalse()
    }

    /**
     * A track change ends one stream before the next starts while the transport stays
     * up; the transport alone keeps that gap playing, as before.
     */
    @Test
    fun `the gap between two streams on a live transport stays playing`() {
        assertThat(playing(transportStreaming = true, streamActive = false)).isTrue()
    }

    /** The player list empties when MA drops with Sendspin; that is not another choice. */
    @Test
    fun `no observed selection during a held drop counts as unchanged`() {
        assertThat(playing(selectedPlayerId = null, transportStreaming = false)).isTrue()
    }

    @Test
    fun `no observed selection outside a drop is not playing`() {
        assertThat(playing(selectedPlayerId = null, transportStreaming = true)).isFalse()
    }

    @Test
    fun `another selected player is not playing even during a held drop`() {
        assertThat(playing(selectedPlayerId = "other", transportStreaming = false)).isFalse()
    }

    /** A pause during the drop drops the intent and idles the output. */
    @Test
    fun `a paused output during a held drop is not playing`() {
        assertThat(playing(intent = false, transportStreaming = false)).isFalse()
        assertThat(playing(outputSynced = false, transportStreaming = false)).isFalse()
    }

    @Test
    fun `a server reporting zero speed is not playing`() {
        assertThat(playing(serverSpeedPositive = false)).isFalse()
    }

    @Test
    fun `no local player is never playing`() {
        assertThat(playing(localPlayerId = null, selectedPlayerId = null, transportStreaming = false)).isFalse()
    }

    @Test
    fun `audio flows on a live transport and through a held drop only`() {
        assertThat(LocalPlayingPolicy.flowing(transportStreaming = true, streamActive = false)).isTrue()
        assertThat(LocalPlayingPolicy.flowing(transportStreaming = false, streamActive = true)).isTrue()
        assertThat(LocalPlayingPolicy.flowing(transportStreaming = false, streamActive = false)).isFalse()
    }

    @Test
    fun `the session shows the server's playing state`() {
        assertThat(LocalPlayingPolicy.shownPlaying(serverPlaying = true, localPlayerSelected = true, localPausePending = false))
            .isTrue()
        assertThat(LocalPlayingPolicy.shownPlaying(serverPlaying = false, localPlayerSelected = true, localPausePending = false))
            .isFalse()
    }

    /** MA still says PLAYING after a pause it has not heard, but the phone is paused. */
    @Test
    fun `a pause MA has not heard shows the phone's own player paused`() {
        assertThat(LocalPlayingPolicy.shownPlaying(serverPlaying = true, localPlayerSelected = true, localPausePending = true))
            .isFalse()
    }

    @Test
    fun `an owed pause of the phone does not change another selected player`() {
        assertThat(LocalPlayingPolicy.shownPlaying(serverPlaying = true, localPlayerSelected = false, localPausePending = true))
            .isTrue()
    }

    private fun disconnected(
        serverPlaying: Boolean = true,
        localPlayerSelected: Boolean = true,
        localPausePending: Boolean = false,
        localStreamActive: Boolean = true,
    ) = LocalPlayingPolicy.shownPlayingDisconnected(serverPlaying, localPlayerSelected, localPausePending, localStreamActive)

    @Test
    fun `while disconnected the phone's own player shows playing while its buffer plays`() {
        assertThat(disconnected()).isTrue()
    }

    /** The stale server state still says PLAYING after the buffered audio has played out. */
    @Test
    fun `while disconnected a played out buffer shows stopped`() {
        assertThat(disconnected(localStreamActive = false)).isFalse()
    }

    @Test
    fun `while disconnected a pause MA has not heard shows paused`() {
        assertThat(disconnected(localPausePending = true)).isFalse()
    }

    @Test
    fun `while disconnected another selected player shows stopped`() {
        assertThat(disconnected(localPlayerSelected = false)).isFalse()
    }

    private companion object {
        const val LOCAL = "local"
    }
}
