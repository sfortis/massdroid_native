package net.asksakis.massdroidv2.domain.player

import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import org.junit.Test

/**
 * Behavioural lock for the single owner of a volume step.
 *
 * Both the activity and the MediaSession deliver the same presses here, and
 * which of them runs depends only on whether the app happens to be in front.
 * That is exactly the kind of split that hides bugs until someone holds a
 * button on a real device: measured on 2026-08-01, one path sent 18
 * `group_volume` commands in 700 ms and dropped a group from 36 to 0 while the
 * other paced the identical presses correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VolumeKeyControllerTest {

    private val phone = player("phone", volume = 40)
    private val group = player(
        "syncgroup",
        volume = 0,
        groupVolume = 30,
        childs = listOf("syncgroup", "speaker"),
    )
    private val mutedMember = player("speaker", volume = 0, muted = true)

    private fun player(
        id: String,
        volume: Int = 40,
        groupVolume: Int? = null,
        childs: List<String> = emptyList(),
        muted: Boolean = false,
    ) = Player(
        playerId = id,
        displayName = id,
        volumeLevel = volume,
        groupVolume = groupVolume,
        groupChilds = childs,
        volumeMuted = muted,
    )

    /** A repository that keeps the optimistic level, as the real one does. */
    private fun repo(
        selected: Player?,
        all: List<Player> = listOfNotNull(selected),
        selectedFlow: MutableStateFlow<Player?> = MutableStateFlow(selected),
    ): PlayerRepository {
        val playersFlow = MutableStateFlow(all)
        val repository = mockk<PlayerRepository>(relaxed = true)
        every { repository.selectedPlayer } returns selectedFlow
        every { repository.players } returns playersFlow
        every { repository.applyVolumeOptimistic(any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            val level = secondArg<Int>()
            selectedFlow.value = selectedFlow.value?.takeIf { it.playerId == id }?.let {
                if (it.groupVolume != null) it.copy(volumeLevel = level, groupVolume = level)
                else it.copy(volumeLevel = level)
            } ?: selectedFlow.value
        }
        coEvery { repository.setVolume(any(), any()) } returns Unit
        coEvery { repository.setGroupVolume(any(), any()) } returns Unit
        return repository
    }

    /**
     * Unconfined so a send runs the moment it is launched, with the trailing
     * flush still on virtual time. Mirrors SendspinVolumeCoordinatorTest.
     */
    private fun TestScope.controller(
        repository: PlayerRepository,
        clock: () -> Long = { 0L },
    ) = VolumeKeyController(repository, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), clock)

    // --- pacing ---

    @Test
    fun `a held key moves the level on every repeat but does not send every one`() = runTest {
        val repository = repo(phone)
        var clock = 0L
        val controller = controller(repository) { clock }

        // 10 repeats, 50 ms apart, exactly as Android delivers them.
        var last = 0
        repeat(10) {
            clock = it * 50L
            last = controller.step(up = true)!!
        }
        advanceUntilIdle()

        // Every repeat moved the level: 40 + 10*3.
        assertThat(last).isEqualTo(70)
        // But only the ones a throttle window apart reached the server.
        coVerify(atMost = 5) { repository.setVolume("phone", any()) }
        coVerify(atLeast = 2) { repository.setVolume("phone", any()) }
    }

    @Test
    fun `the final level always lands, even when the last steps were throttled`() = runTest {
        val repository = repo(phone)
        var clock = 0L
        val controller = controller(repository) { clock }

        controller.step(up = true)          // 43, sent immediately
        clock = 40
        val last = controller.step(up = true)!!  // 46, inside the throttle window
        advanceUntilIdle()                  // lets the trailing flush fire

        assertThat(last).isEqualTo(46)
        coVerify { repository.setVolume("phone", 46) }
    }

    @Test
    fun `a release lands the held level without waiting for the timer`() = runTest {
        val repository = repo(phone)
        var clock = 0L
        val controller = controller(repository) { clock }

        controller.step(up = true)
        clock = 40
        controller.step(up = true)          // throttled
        controller.flush()
        advanceUntilIdle()

        coVerify { repository.setVolume("phone", 46) }
    }

    @Test
    fun `flushing with nothing held sends nothing`() = runTest {
        val repository = repo(phone)
        val controller = controller(repository)

        controller.flush()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.setVolume(any(), any()) }
        coVerify(exactly = 0) { repository.setGroupVolume(any(), any()) }
    }

    @Test
    fun `only one command is in flight, and the last level always wins`() = runTest {
        // Found on a device: every send ran in its own coroutine and each waits
        // for a reply, so a hold put several in flight and the speaker settled
        // on a value from the MIDDLE of the sequence - a later write overtaken
        // by an earlier one.
        val repository = repo(phone)
        val started = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.setVolume(any(), any()) } coAnswers {
            started += secondArg<Int>()
            gate.await()          // hold the first command open
        }
        var clock = 0L
        val controller = controller(repository) { clock }

        controller.step(up = true)      // 43 -> goes out, then blocks
        clock = 200
        controller.step(up = true)      // 46 -> queued behind it
        clock = 400
        controller.step(up = true)      // 49 -> replaces 46, it is newer
        advanceUntilIdle()

        assertThat(started).containsExactly(43)   // nothing else started yet
        gate.complete(Unit)
        advanceUntilIdle()

        // 46 never goes out: it was obsolete before the line was free.
        assertThat(started).containsExactly(43, 49).inOrder()
    }

    // --- group vs single player ---

    @Test
    fun `a group moves through group volume, from the group level`() = runTest {
        val repository = repo(group, listOf(group, mutedMember))
        val controller = controller(repository)

        val level = controller.step(up = true)
        advanceUntilIdle()

        // 30 (the group level), not 0 (the parent's own).
        assertThat(level).isEqualTo(33)
        coVerify { repository.setGroupVolume("syncgroup", 33) }
        coVerify(exactly = 0) { repository.setVolume("syncgroup", any()) }
    }

    @Test
    fun `a throttled group step still flushes as a group command`() = runTest {
        // Regression: the flush used to recompute isGroup from the player list
        // and fall back to "not a group", which sent `volume_set` to a group
        // parent. That is a no-op server-side, so the last step of a hold was
        // silently lost.
        val repository = repo(group, all = emptyList())
        var clock = 0L
        val controller = controller(repository) { clock }

        controller.step(up = true)
        clock = 40
        controller.step(up = true)   // throttled, only the trailing flush sends it
        advanceUntilIdle()

        coVerify { repository.setGroupVolume("syncgroup", 36) }
        coVerify(exactly = 0) { repository.setVolume("syncgroup", any()) }
    }

    @Test
    fun `switching player mid-hold sends the level the old one was holding`() = runTest {
        // Found in review. A single pending slot was overwritten by the new
        // player's step, so the previous player's last change never reached the
        // server - it only ever existed as an optimistic value on screen.
        val kitchen = player("kitchen", volume = 20)
        val selected = MutableStateFlow<Player?>(phone)
        val repository = repo(phone, listOf(phone, kitchen), selected)
        var clock = 0L
        val controller = controller(repository) { clock }

        controller.step(up = true)                 // phone -> 43, sent
        clock = 40
        controller.step(up = true)                 // phone -> 46, throttled
        selected.value = kitchen
        controller.step(up = true)                 // kitchen -> 23
        advanceUntilIdle()

        coVerify { repository.setVolume("phone", 46) }
        coVerify { repository.setVolume("kitchen", 23) }
    }

    // --- mute ---

    @Test
    fun `turning a group up unmutes the member that is actually muted`() = runTest {
        val repository = repo(group, listOf(group, mutedMember))
        val controller = controller(repository)

        controller.step(up = true)
        advanceUntilIdle()

        // The group itself reports volume_muted=null; the silence lives on the
        // member, which is what has to be unmuted.
        coVerify { repository.toggleMute("speaker", false) }
        coVerify(exactly = 0) { repository.toggleMute("syncgroup", any()) }
    }

    @Test
    fun `turning down does not unmute anything`() = runTest {
        val repository = repo(group, listOf(group, mutedMember))
        val controller = controller(repository)

        controller.step(up = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.toggleMute(any(), any()) }
    }

    @Test
    fun `an unmuted player is left alone`() = runTest {
        val repository = repo(phone)
        val controller = controller(repository)

        controller.step(up = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.toggleMute(any(), any()) }
    }

    // --- edges ---

    @Test
    fun `with no player selected nothing happens at all`() = runTest {
        val repository = repo(null, emptyList())
        val controller = controller(repository)

        assertThat(controller.step(up = true)).isNull()
        assertThat(controller.setLevel(50)).isNull()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.setVolume(any(), any()) }
    }

    @Test
    fun `an absolute level is clamped to the 0-100 range`() = runTest {
        val repository = repo(phone)
        val controller = controller(repository)

        assertThat(controller.setLevel(140)).isEqualTo(100)
        assertThat(controller.setLevel(-20)).isEqualTo(0)
    }

    @Test
    fun `steps clamp rather than running past the ends`() = runTest {
        val repository = repo(player("phone", volume = 99))
        val controller = controller(repository)

        assertThat(controller.step(up = true)).isEqualTo(100)
    }
}
