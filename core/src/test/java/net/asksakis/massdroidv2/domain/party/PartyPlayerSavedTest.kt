package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.PartyPlayerSetting
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueConfigOption
import org.junit.Test

/**
 * After the server acknowledged a new party player, the reads made during the plugin's reload
 * can fail or answer from before the save; the screen must not fall back to an empty card or to
 * the old choice then.
 */
class PartyPlayerSavedTest {

    private val players = listOf(Player(playerId = "car-id", displayName = "MassDroid AAOS"))
    private val shownTarget = PartyTargetSnapshot("kitchen", emptyList())
    private val options = listOf(QueueConfigOption("kitchen", "Kitchen"), QueueConfigOption("car-id", "MassDroid AAOS"))
    private val shownSetting = PartyPlayerSetting(PartyPlayerSetting.AUTO, options)

    @Test
    fun `a failed target read keeps the target on screen`() {
        val target = PartyPlayerSaved.target(shownTarget, Result.failure(IllegalStateException()), players)

        assertThat(target).isSameInstanceAs(shownTarget)
    }

    @Test
    fun `a target read, an empty one included, replaces the target on screen`() {
        assertThat(PartyPlayerSaved.target(shownTarget, Result.success("car-id"), players))
            .isEqualTo(PartyTargetSnapshot("car-id", players))
        assertThat(PartyPlayerSaved.target(shownTarget, Result.success(null), players))
            .isEqualTo(PartyTargetSnapshot(null, players))
    }

    @Test
    fun `a failed, empty or older setting read shows the saved value`() {
        val older = PartyPlayerSetting(PartyPlayerSetting.AUTO, options)

        listOf<Result<PartyPlayerSetting?>>(
            Result.failure(IllegalStateException()),
            Result.success(null),
            Result.success(older),
        ).forEach { read ->
            assertThat(PartyPlayerSaved.setting(shownSetting, read, "car-id"))
                .isEqualTo(PartyPlayerSetting("car-id", options))
        }
    }

    @Test
    fun `a setting read that carries the saved value is taken with its own options`() {
        val read = PartyPlayerSetting("car-id", options.take(1) + QueueConfigOption("car-id", "Renamed car"))

        assertThat(PartyPlayerSaved.setting(shownSetting, Result.success(read), "car-id")).isSameInstanceAs(read)
    }
}
