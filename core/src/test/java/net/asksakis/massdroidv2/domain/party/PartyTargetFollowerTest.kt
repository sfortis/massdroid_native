package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import org.junit.Test

/**
 * The Party screen changes its player card only when a re-read of the party player gives a
 * different target, so a busy house does not make the card, and the QR code sized around it,
 * jump about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartyTargetFollowerTest {

    private fun players(kitchen: PlaybackState, bedroom: PlaybackState = PlaybackState.IDLE) = listOf(
        Player(playerId = "kitchen", displayName = "Kitchen", state = kitchen),
        Player(playerId = "bedroom", displayName = "Bedroom", state = bedroom)
    )

    private val live = MutableStateFlow(players(kitchen = PlaybackState.PLAYING))
    private var serverTarget: String? = "kitchen"
    private var reads = 0
    private val snapshots = mutableListOf<PartyTargetSnapshot>()

    private fun TestScope.follow(fail: Boolean = false) {
        backgroundScope.launch {
            PartyTargetFollower.updates(live) {
                reads++
                if (fail) error("timeout")
                serverTarget
            }.collect { snapshots += it }
        }
        runCurrent()
    }

    private fun PartyTargetSnapshot.shown() = resolve(PartyMode.VENUE, selectedQueueId = "phone", thisPhonePlayerId = "phone")

    @Test
    fun `a players change without a re-read does not change the shown target`() = runTest {
        val shown = PartyTargetSnapshot("kitchen", live.value).shown()
        follow()

        live.value = players(kitchen = PlaybackState.IDLE)
        advanceTimeBy(PartyTargetFollower.SETTLE_MS - 1)
        runCurrent()

        assertThat(snapshots).isEmpty()
        assertThat(shown).isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.Named("Kitchen"), PlaybackState.PLAYING))
    }

    @Test
    fun `a failed re-read keeps the shown target`() = runTest {
        follow(fail = true)

        live.value = players(kitchen = PlaybackState.IDLE)
        advanceTimeBy(PartyTargetFollower.SETTLE_MS * 5)
        runCurrent()

        assertThat(reads).isEqualTo(1)
        assertThat(snapshots).isEmpty()
    }

    /** The state the screen shows is a StateFlow, so an equal target emits nothing new. */
    @Test
    fun `a re-read that names the same target resolves to the same shown target`() = runTest {
        val before = PartyTargetSnapshot("kitchen", live.value).shown()
        follow()

        live.value = players(kitchen = PlaybackState.PLAYING, bedroom = PlaybackState.PLAYING)
        advanceTimeBy(PartyTargetFollower.SETTLE_MS + 1)
        runCurrent()

        assertThat(snapshots).hasSize(1)
        assertThat(snapshots.single().shown()).isEqualTo(before)
    }

    @Test
    fun `the shown target changes once the re-read answers`() = runTest {
        follow()

        serverTarget = "bedroom"
        live.value = players(kitchen = PlaybackState.IDLE, bedroom = PlaybackState.PLAYING)
        advanceTimeBy(PartyTargetFollower.SETTLE_MS + 1)
        runCurrent()

        assertThat(snapshots.single().shown())
            .isEqualTo(PartyTarget.OtherPlayer(PartyPlayerName.Named("Bedroom"), PlaybackState.PLAYING))
    }

    @Test
    fun `a burst of player changes reads the party player once`() = runTest {
        follow()

        repeat(5) {
            live.value = players(kitchen = if (it % 2 == 0) PlaybackState.IDLE else PlaybackState.PLAYING)
            advanceTimeBy(PartyTargetFollower.SETTLE_MS / 2)
            runCurrent()
        }
        advanceTimeBy(PartyTargetFollower.SETTLE_MS * 5)
        runCurrent()

        assertThat(reads).isEqualTo(1)
    }

    @Test
    fun `a change that is not a start or a stop reads nothing`() = runTest {
        follow()

        live.value = live.value.map { it.copy(displayName = it.displayName + " 2") }
        advanceTimeBy(PartyTargetFollower.SETTLE_MS * 5)
        runCurrent()

        assertThat(reads).isEqualTo(0)
    }
}
