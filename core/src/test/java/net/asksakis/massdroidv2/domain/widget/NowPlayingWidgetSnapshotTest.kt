package net.asksakis.massdroidv2.domain.widget

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.NowPlaying
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import org.junit.Test

class NowPlayingWidgetSnapshotTest {

    private val player = Player(
        playerId = "pi",
        displayName = "Kitchen",
        state = PlaybackState.PLAYING,
        currentMedia = NowPlaying(title = "View2", artist = "Sasha", imageUrl = "http://x/a.jpg")
    )

    @Test
    fun `a playing player fills every field`() {
        val s = NowPlayingWidgetSnapshot.from(player, connected = true)
        assertThat(s).isEqualTo(
            NowPlayingWidgetSnapshot("pi", "Kitchen", "View2", "Sasha", "http://x/a.jpg", isPlaying = true, connected = true)
        )
        assertThat(s.hasTrack).isTrue()
    }

    @Test
    fun `no selected player keeps only the connection state`() {
        val s = NowPlayingWidgetSnapshot.from(null, connected = false)
        assertThat(s).isEqualTo(NowPlayingWidgetSnapshot(connected = false))
        assertThat(s.hasTrack).isFalse()
    }

    @Test
    fun `a paused player with no media is not playing and has no track`() {
        val s = NowPlayingWidgetSnapshot.from(player.copy(state = PlaybackState.PAUSED, currentMedia = null), true)
        assertThat(s.isPlaying).isFalse()
        assertThat(s.hasTrack).isFalse()
        assertThat(s.playerName).isEqualTo("Kitchen")
    }

    @Test
    fun `losing the connection keeps the last track but marks it offline and stopped`() {
        val last = NowPlayingWidgetSnapshot.from(player, connected = true)
        val next = NowPlayingWidgetSnapshot.next(last, player = null, connected = false)
        assertThat(next).isEqualTo(last.copy(connected = false, isPlaying = false))
    }

    /** The S25 widget showed paused while the phone played its buffer through a drop. */
    @Test
    fun `the phone's own player keeps playing through a dropped connection`() {
        val last = NowPlayingWidgetSnapshot.from(player, connected = true, localPlayerId = "pi")
        val next = NowPlayingWidgetSnapshot.next(
            last, player, connected = false, localPlayerId = "pi", localStreamActive = true
        )
        assertThat(next).isEqualTo(last.copy(connected = false, isPlaying = true))
    }

    /** The selected player still says PLAYING, but the buffered audio has played out. */
    @Test
    fun `the phone's own player shows stopped once its buffer has played out`() {
        val last = NowPlayingWidgetSnapshot.from(player, connected = true, localPlayerId = "pi")
        val next = NowPlayingWidgetSnapshot.next(
            last, player, connected = false, localPlayerId = "pi", localStreamActive = false
        )
        assertThat(next?.isPlaying).isFalse()
    }

    @Test
    fun `a pause of the phone during a drop shows paused`() {
        val last = NowPlayingWidgetSnapshot.from(player, connected = true, localPlayerId = "pi")
        val next = NowPlayingWidgetSnapshot.next(
            last, player, connected = false, localPlayerId = "pi", localPauseOwed = true, localStreamActive = true
        )
        assertThat(next?.isPlaying).isFalse()
    }

    @Test
    fun `another player shows stopped while disconnected as before`() {
        val last = NowPlayingWidgetSnapshot.from(player, connected = true, localPlayerId = "s25")
        val next = NowPlayingWidgetSnapshot.next(
            last, player, connected = false, localPlayerId = "s25", localStreamActive = true
        )
        assertThat(next?.isPlaying).isFalse()
    }

    @Test
    fun `a pause MA has not heard shows paused on a live connection too`() {
        val next = NowPlayingWidgetSnapshot.next(
            NowPlayingWidgetSnapshot.Empty, player, connected = true, localPlayerId = "pi", localPauseOwed = true
        )
        assertThat(next?.isPlaying).isFalse()
    }

    @Test
    fun `connected with the selection still being restored publishes nothing`() {
        val last = NowPlayingWidgetSnapshot.from(player, connected = true)
        assertThat(NowPlayingWidgetSnapshot.next(last, player = null, connected = true)).isNull()
    }

    @Test
    fun `a selected player on a live connection replaces the snapshot`() {
        val other = player.copy(playerId = "s25", displayName = "MassDroid S25")
        val next = NowPlayingWidgetSnapshot.next(NowPlayingWidgetSnapshot.Empty, other, connected = true)
        assertThat(next?.playerName).isEqualTo("MassDroid S25")
    }
}
