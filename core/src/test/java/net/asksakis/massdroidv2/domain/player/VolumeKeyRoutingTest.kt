package net.asksakis.massdroidv2.domain.player

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Player
import org.junit.Test

/**
 * Pins where a hardware volume key press goes.
 *
 * Worth pinning because the failure mode is silent and only shows on a device:
 * the key handler used to act on `ACTION_DOWN`, which One UI's volume panel
 * consumes before an activity sees it (measured on a Samsung S25: 47 ACTION_UP
 * and zero ACTION_DOWN reached the app), so the rocker did nothing at all while
 * still being swallowed. Nothing in the code had changed - a system update was
 * enough. The routing below is the other half of that decision.
 */
class VolumeKeyRoutingTest {

    private val phoneSendspinId = "5046ad54-4f5a-43c0-8278-e0d5a0a51dc9"

    private fun player(
        id: String,
        volume: Int = 40,
        groupVolume: Int? = null,
        childs: List<String> = emptyList()
    ) = Player(
        playerId = id,
        displayName = id,
        volumeLevel = volume,
        groupVolume = groupVolume,
        groupChilds = childs
    )

    @Test
    fun `no selected player leaves the key to the system`() {
        assertThat(volumeKeyTarget(null, phoneSendspinId)).isEqualTo(VolumeKeyTarget.System)
    }

    @Test
    fun `this phone's own Sendspin output uses the OS stream`() {
        val target = volumeKeyTarget(player(phoneSendspinId), phoneSendspinId)
        assertThat(target).isEqualTo(VolumeKeyTarget.LocalStream)
    }

    @Test
    fun `a remote speaker is controlled through Music Assistant`() {
        val target = volumeKeyTarget(player("upsendspinclisendspinpi"), phoneSendspinId)
        assertThat(target).isEqualTo(
            VolumeKeyTarget.RemotePlayer("upsendspinclisendspinpi", isGroup = false)
        )
    }

    @Test
    fun `a group the phone is not part of moves the group volume`() {
        val group = player("syncgroup", childs = listOf("syncgroup", "kitchen", "bedroom"))
        assertThat(volumeKeyTarget(group, phoneSendspinId))
            .isEqualTo(VolumeKeyTarget.RemotePlayer("syncgroup", isGroup = true))
    }

    @Test
    fun `a group containing this phone adjusts only the phone`() {
        // Holding the phone, the keys should change what the phone is putting
        // out, not shout across the whole house.
        val group = player("syncgroup", childs = listOf("syncgroup", phoneSendspinId, "kitchen"))
        assertThat(volumeKeyTarget(group, phoneSendspinId)).isEqualTo(VolumeKeyTarget.LocalStream)
    }

    @Test
    fun `with no Sendspin client every player is remote`() {
        val target = volumeKeyTarget(player("kitchen"), sendspinClientId = null)
        assertThat(target).isEqualTo(VolumeKeyTarget.RemotePlayer("kitchen", isGroup = false))
    }

    @Test
    fun `a group with only itself as child is not a group`() {
        val solo = player("player1", childs = listOf("player1"))
        assertThat(volumeKeyTarget(solo, phoneSendspinId))
            .isEqualTo(VolumeKeyTarget.RemotePlayer("player1", isGroup = false))
    }

    @Test
    fun `a group step moves the group level, not the parent's own`() {
        val group = player("syncgroup", volume = 10, groupVolume = 60)
        assertThat(remoteVolumeStep(group, isGroup = true, delta = 5)).isEqualTo(65)
        assertThat(remoteVolumeStep(group, isGroup = false, delta = 5)).isEqualTo(15)
    }

    @Test
    fun `a group with no reported group volume falls back to its own`() {
        val group = player("syncgroup", volume = 30, groupVolume = null)
        assertThat(remoteVolumeStep(group, isGroup = true, delta = 5)).isEqualTo(35)
    }

    @Test
    fun `steps clamp to the 0-100 range`() {
        assertThat(remoteVolumeStep(player("p", volume = 98), isGroup = false, delta = 5)).isEqualTo(100)
        assertThat(remoteVolumeStep(player("p", volume = 2), isGroup = false, delta = -5)).isEqualTo(0)
    }

    // --- which edges act ---

    @Test
    fun `a press steps on its DOWN edge`() {
        assertThat(volumeKeyAction(isDown = true, downTime = 1000, lastCountedDownTime = 0))
            .isEqualTo(VolumeKeyAction.STEP)
    }

    @Test
    fun `every repeat of a hold steps`() {
        // Pacing is VolumeKeyController's job, so a repeat is a full step here:
        // the level must move on screen at the rate the key repeats.
        assertThat(volumeKeyAction(isDown = true, downTime = 1000, lastCountedDownTime = 1000))
            .isEqualTo(VolumeKeyAction.STEP)
    }

    @Test
    fun `the release of a press that already stepped only flushes`() {
        // Same downTime as the DOWN we already acted on: stepping again here
        // would move the volume twice for one press.
        assertThat(volumeKeyAction(isDown = false, downTime = 1000, lastCountedDownTime = 1000))
            .isEqualTo(VolumeKeyAction.FLUSH)
    }

    @Test
    fun `a release whose DOWN never arrived steps`() {
        // The measured state where only ACTION_UP reaches the activity: without
        // this the key would do nothing at all.
        assertThat(volumeKeyAction(isDown = false, downTime = 1000, lastCountedDownTime = 0))
            .isEqualTo(VolumeKeyAction.STEP)
    }
}
