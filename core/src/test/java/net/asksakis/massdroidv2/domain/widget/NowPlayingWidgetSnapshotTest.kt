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
}
