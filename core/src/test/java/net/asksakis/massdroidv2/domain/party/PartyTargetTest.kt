package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.model.Player
import org.junit.Test

/**
 * Which player gets the guests' songs, as the Party screen names it. In the car the plugin's
 * automatic choice can pick a home speaker that is playing, and that has to be told apart
 * from the player the app is controlling.
 */
class PartyTargetTest {

    private val players = listOf(
        Player(playerId = "phone", displayName = "Phone"),
        Player(playerId = "kitchen", displayName = "Kitchen")
    )

    private fun resolve(target: String?, mode: PartyMode = PartyMode.VENUE, selected: String? = "kitchen") =
        PartyTarget.resolve(target, mode, players, selected, thisPhonePlayerId = "phone")

    @Test
    fun `the selected player is named as the party player`() {
        assertThat(resolve("kitchen"))
            .isEqualTo(PartyTarget.SelectedPlayer(PartyPlayerName.Named("Kitchen")))
    }

    @Test
    fun `this phone's own player is called this phone`() {
        assertThat(resolve("phone", selected = "phone"))
            .isEqualTo(PartyTarget.SelectedPlayer(PartyPlayerName.ThisPhone))
    }

    @Test
    fun `another player is told apart from the selected one`() {
        assertThat(resolve("phone")).isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.ThisPhone))
        assertThat(resolve("kitchen", selected = "phone"))
            .isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.Named("Kitchen")))
    }

    @Test
    fun `a queue no known player owns is another player without a name`() {
        assertThat(resolve("syncgroup_unknown")).isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.Unknown))
    }

    @Test
    fun `no queue from the server means no player`() {
        assertThat(resolve(null)).isEqualTo(PartyTarget.NoPlayer)
    }

    @Test
    fun `the silent disco plays on the guests' own devices`() {
        assertThat(resolve("party_session_queue", mode = PartyMode.REMOTE)).isEqualTo(PartyTarget.GuestSession)
    }

    @Test
    fun `a silent disco without its player is not told to start playing`() {
        assertThat(resolve(null, mode = PartyMode.REMOTE)).isEqualTo(PartyTarget.GuestSessionUnavailable)
    }

    @Test
    fun `nothing selected yet makes any party player another one`() {
        assertThat(resolve("kitchen", selected = null))
            .isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.Named("Kitchen")))
    }
}
