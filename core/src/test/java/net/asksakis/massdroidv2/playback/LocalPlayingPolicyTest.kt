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

    private companion object {
        const val LOCAL = "local"
    }
}
