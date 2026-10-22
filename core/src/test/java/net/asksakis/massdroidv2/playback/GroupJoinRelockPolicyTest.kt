package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins where a group join may seek; a seek at the very end of a track stalled MA. */
class GroupJoinRelockPolicyTest {

    @Test
    fun `a position inside the track is used as it is`() {
        assertThat(GroupJoinRelockPolicy.seekPosition(120.5, 383.0)).isEqualTo(120.5)
    }

    @Test
    fun `a position past the end of the track is not trusted`() {
        // Measured: MA reported 462 s on a 383 s track during a crossfade.
        assertThat(GroupJoinRelockPolicy.seekPosition(459.6, 383.0)).isNull()
    }

    @Test
    fun `a position in the last seconds of the track is not trusted`() {
        assertThat(GroupJoinRelockPolicy.seekPosition(382.0, 383.0)).isNull()
        assertThat(GroupJoinRelockPolicy.seekPosition(378.0, 383.0)).isEqualTo(378.0)
    }

    @Test
    fun `a track with no known length keeps its position`() {
        assertThat(GroupJoinRelockPolicy.seekPosition(900.0, 0.0)).isEqualTo(900.0)
    }

    @Test
    fun `a negative position starts at zero`() {
        assertThat(GroupJoinRelockPolicy.seekPosition(-1.0, 383.0)).isEqualTo(0.0)
    }
}
