package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins which timed-out commands may be sent again.
 *
 * Every command except auth used to be retried, which was wrong for the ones that add
 * or step: a timeout means the answer did not arrive, not that the command did not run,
 * so a server that merely answers late gets the same request applied twice.
 *
 * Measured on 2026-08-28: a Smart Mix sent the same 27-track `play_media` twice, 140 ms
 * apart, and every failed attempt cost 60 seconds instead of 30 because the retry
 * doubled the wait. The build took over five minutes and looked frozen.
 *
 * This governs the TIMEOUT retry only. A failed send is retried for every command,
 * transport included, because a message that never left cannot have taken effect: the
 * transport commands the car and TV send are fire-and-forget and only ever reach that
 * path. Returning false here therefore does not stop `next` from being re-sent when the
 * socket drops, which is what makes the distinction worth keeping.
 */
class MaCommandRetryTest {

    @Test
    fun `play_media is never retried, because a late answer would queue the tracks twice`() {
        assertThat(isRetryableCommand(MaCommands.PlayerQueues.PLAY_MEDIA)).isFalse()
    }

    @Test
    fun `commands that create leave two objects if repeated`() {
        assertThat(isRetryableCommand(MaCommands.Music.PLAYLISTS_CREATE)).isFalse()
        assertThat(isRetryableCommand(MaCommands.PlayerQueues.SAVE_AS_PLAYLIST)).isFalse()
    }

    @Test
    fun `commands that append or address by position are not retried`() {
        assertThat(isRetryableCommand(MaCommands.Music.PLAYLISTS_ADD_TRACKS)).isFalse()
        assertThat(isRetryableCommand(MaCommands.Music.PLAYLISTS_REMOVE_TRACKS)).isFalse()
        assertThat(isRetryableCommand(MaCommands.PlayerQueues.DELETE_ITEM)).isFalse()
        assertThat(isRetryableCommand(MaCommands.PlayerQueues.MOVE_ITEM)).isFalse()
    }

    @Test
    fun `transport steps are not retried, or the listener skips two tracks`() {
        assertThat(isRetryableCommand(MaCommands.Players.CMD_NEXT)).isFalse()
        assertThat(isRetryableCommand(MaCommands.Players.CMD_PREVIOUS)).isFalse()
    }

    @Test
    fun `auth is not retried`() {
        assertThat(isRetryableCommand(MaCommands.Auth.AUTH)).isFalse()
        assertThat(isRetryableCommand(MaCommands.Auth.LOGIN)).isFalse()
    }

    @Test
    fun `reads stay retryable, which is what the retry was for`() {
        listOf(
            MaCommands.Music.TRACKS_LIBRARY_ITEMS,
            MaCommands.Music.ARTISTS_LIBRARY_ITEMS,
            MaCommands.Music.SEARCH,
            MaCommands.Music.BROWSE,
            MaCommands.Music.RECOMMENDATIONS,
            MaCommands.Music.SIMILAR_ARTISTS,
            MaCommands.Music.SIMILAR_TRACKS,
            MaCommands.Music.ARTIST_TOP_TRACKS,
            MaCommands.Players.ALL,
            MaCommands.PlayerQueues.ITEMS,
            MaCommands.ConfigPlayers.GET,
            MaCommands.ConfigPlayerQueues.GET,
            MaCommands.ConfigCore.GET_VALUE
        ).forEach { command ->
            assertThat(isRetryableCommand(command)).isTrue()
        }
    }

    @Test
    fun `writes that set a value stay retryable, since repeating them changes nothing`() {
        // Landing on the same state twice is harmless, and these are exactly the
        // commands a flaky connection should keep trying.
        listOf(
            MaCommands.Players.CMD_VOLUME_SET,
            MaCommands.Players.CMD_POWER,
            MaCommands.PlayerQueues.REPEAT,
            MaCommands.PlayerQueues.SHUFFLE,
            MaCommands.PlayerQueues.CLEAR,
            MaCommands.PlayerQueues.PLAY_INDEX,
            MaCommands.PlayerQueues.SET_AUTOPLAY_ENABLED,
            MaCommands.Music.FAVORITES_ADD,
            MaCommands.Music.FAVORITES_REMOVE,
            MaCommands.Music.MARK_PLAYED,
            MaCommands.ConfigPlayers.SAVE,
            MaCommands.ConfigPlayerQueues.SAVE
        ).forEach { command ->
            assertThat(isRetryableCommand(command)).isTrue()
        }
    }

    @Test
    fun `an unknown command is retryable, so reads added later keep working`() {
        // The list names what is unsafe. A command that adds or steps must be added to
        // it; the KDoc on the set says so.
        assertThat(isRetryableCommand("music/some/future_read")).isTrue()
    }
}
