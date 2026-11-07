package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import org.junit.Test

/**
 * Which player gets the guests' songs, as the Party screen names it. In the car the plugin's
 * automatic choice can pick a home speaker that is playing, and that has to be told apart
 * from the player the app is controlling. While nothing plays, there is no player to name.
 */
class PartyTargetTest {

    private fun resolve(
        target: String?,
        mode: PartyMode = PartyMode.VENUE,
        selected: String? = "kitchen",
        kitchen: PlaybackState = PlaybackState.PLAYING,
        phone: PlaybackState = PlaybackState.PLAYING
    ) = PartyTarget.resolve(
        targetQueueId = target,
        mode = mode,
        players = listOf(
            Player(playerId = "phone", displayName = "Phone", state = phone),
            Player(playerId = "kitchen", displayName = "Kitchen", state = kitchen)
        ),
        selectedQueueId = selected,
        thisPhonePlayerId = "phone"
    )

    @Test
    fun `the selected player playing is named as the party player`() {
        assertThat(resolve("kitchen"))
            .isEqualTo(PartyTarget.SelectedPlayer(PartyPlayerName.Named("Kitchen")))
    }

    @Test
    fun `a paused party player is still named`() {
        assertThat(resolve("kitchen", kitchen = PlaybackState.PAUSED))
            .isEqualTo(PartyTarget.SelectedPlayer(PartyPlayerName.Named("Kitchen")))
    }

    @Test
    fun `this phone's own player is called this phone`() {
        assertThat(resolve("phone", selected = "phone"))
            .isEqualTo(PartyTarget.SelectedPlayer(PartyPlayerName.ThisPhone))
    }

    @Test
    fun `another player playing is told apart from the selected one`() {
        assertThat(resolve("phone")).isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.ThisPhone, PlaybackState.PLAYING))
        assertThat(resolve("kitchen", selected = "phone"))
            .isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.Named("Kitchen"), PlaybackState.PLAYING))
    }

    @Test
    fun `nothing selected yet makes any playing party player another one`() {
        assertThat(resolve("kitchen", selected = null))
            .isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.Named("Kitchen"), PlaybackState.PLAYING))
    }

    /** The plugin's automatic choice picks some queue even while nothing plays. */
    @Test
    fun `a party player that is not playing while nothing plays is idle`() {
        assertThat(resolve("kitchen", selected = "phone", kitchen = PlaybackState.IDLE, phone = PlaybackState.IDLE))
            .isEqualTo(PartyTarget.Idle)
        assertThat(resolve("phone", kitchen = PlaybackState.IDLE, phone = PlaybackState.IDLE))
            .isEqualTo(PartyTarget.Idle)
    }

    /**
     * The car case with a speaker fixed in the plugin's settings: the phone plays, the home
     * speaker is idle, and the first guest song would start it at home.
     */
    @Test
    fun `an idle fixed player while another plays keeps its name and its state`() {
        assertThat(resolve("kitchen", selected = "phone", kitchen = PlaybackState.IDLE))
            .isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.Named("Kitchen"), PlaybackState.IDLE))
    }

    @Test
    fun `an idle selected player while another plays stays the selected one`() {
        assertThat(resolve("kitchen", kitchen = PlaybackState.IDLE))
            .isEqualTo(PartyTarget.SelectedPlayer(PartyPlayerName.Named("Kitchen")))
    }

    /** Seen on MA 2.10.9: a universal player's queue id that is not in players/all. */
    @Test
    fun `a queue no known player owns is idle`() {
        assertThat(resolve("up00044b8a5325")).isEqualTo(PartyTarget.Idle)
    }

    @Test
    fun `no queue from the server is idle`() {
        assertThat(resolve(null)).isEqualTo(PartyTarget.Idle)
    }

    @Test
    fun `the silent disco plays on the guests' own devices`() {
        assertThat(resolve("party_session_queue", mode = PartyMode.REMOTE)).isEqualTo(PartyTarget.GuestSession)
    }

    @Test
    fun `a silent disco without its player is not told to start playing`() {
        assertThat(resolve(null, mode = PartyMode.REMOTE)).isEqualTo(PartyTarget.GuestSessionUnavailable)
    }
}
