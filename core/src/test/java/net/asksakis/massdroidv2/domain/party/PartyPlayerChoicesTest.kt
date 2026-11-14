package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.PartyPlayerSetting
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueConfigOption
import org.junit.Test

/**
 * The party player selector offers Auto and the players the Party plugin offers, and the card
 * says what Auto picks right now, because the plugin's Auto takes the first playing queue in
 * the server's order, which is often not the player in front of the user.
 */
class PartyPlayerChoicesTest {

    private val kitchen = QueueConfigOption("kitchen", "Kitchen")
    private val car = QueueConfigOption("car-id", "MassDroid AAOS")

    private fun setting(vararg options: QueueConfigOption, value: String = PartyPlayerSetting.AUTO) =
        PartyPlayerSetting(value, options.toList())

    @Test
    fun `auto comes first, then the plugin's players in the plugin's order`() {
        val options = PartyPlayerChoices.options(
            setting(QueueConfigOption(PartyPlayerSetting.AUTO, "Auto (last active player)"), kitchen, car),
            ownPlayerId = null,
            ownLabel = "this car"
        )

        assertThat(options.map { it.value }).containsExactly(PartyPlayerSetting.AUTO, "kitchen", "car-id").inOrder()
    }

    @Test
    fun `auto is listed once, under the app's own name`() {
        val options = PartyPlayerChoices.options(
            setting(QueueConfigOption(PartyPlayerSetting.AUTO, "Auto (last active player)"), kitchen),
            ownPlayerId = null,
            ownLabel = "this car"
        )

        assertThat(options.filter { it.value == PartyPlayerSetting.AUTO }.map { it.title }).containsExactly("Auto")
    }

    @Test
    fun `auto is offered even when the plugin's list lacks it`() {
        val options = PartyPlayerChoices.options(setting(kitchen), ownPlayerId = null, ownLabel = "this car")

        assertThat(options.first().value).isEqualTo(PartyPlayerSetting.AUTO)
    }

    @Test
    fun `this device's own player is marked`() {
        val options = PartyPlayerChoices.options(setting(kitchen, car), ownPlayerId = "car-id", ownLabel = "this car")

        assertThat(options.map { it.title }).containsExactly("Auto", "Kitchen", "MassDroid AAOS (this car)").inOrder()
    }

    @Test
    fun `a fixed player that went offline keeps a selected row under its known name`() {
        val players = listOf(Player(playerId = "garage", displayName = "Garage"))

        val withGarage = PartyPlayerChoices.withCurrent(setting(kitchen, value = "garage"), players)
        val withUnknown = PartyPlayerChoices.withCurrent(setting(kitchen, value = "gone"), players)

        assertThat(withGarage.options).containsExactly(kitchen, QueueConfigOption("garage", "Garage")).inOrder()
        assertThat(withUnknown.options.last()).isEqualTo(QueueConfigOption("gone", PartyPlayerChoices.OFFLINE_TITLE))
    }

    @Test
    fun `a setting whose player is offered or that is auto is left as it is`() {
        val players = listOf(Player(playerId = "kitchen", displayName = "Old kitchen name"))

        assertThat(PartyPlayerChoices.withCurrent(setting(kitchen, value = "kitchen"), players).options)
            .containsExactly(kitchen)
        assertThat(PartyPlayerChoices.withCurrent(setting(kitchen), players).options).containsExactly(kitchen)
    }

    @Test
    fun `auto says what it picks now, and stays plain when it picks nothing`() {
        assertThat(PartyPlayerChoices.autoTitle("cast group")).isEqualTo("Auto (now: cast group)")
        assertThat(PartyPlayerChoices.autoTitle(null)).isEqualTo("Auto")
    }
}
