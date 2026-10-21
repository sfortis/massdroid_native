package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.repository.PlayerRepository.QueueFilterMode
import org.junit.Test

/**
 * A mix's mode is set aside at a disconnect and survives a reconnect that takes several attempts.
 *
 * Setting aside runs on every Disconnected, Connecting and Error state. Its first version cleared
 * the set-aside modes on each run, so the second state of a retried reconnect wiped what the
 * first had kept, which is exactly the handover the change was for.
 */
class GeneratedModeSetAsideTest {

    private val active = mutableMapOf(
        "syncgroup_mix" to QueueFilterMode.SMART_GENERATED,
        "own_album" to QueueFilterMode.NORMAL,
        "server_radio" to QueueFilterMode.RADIO_SMART,
    )
    private val awaiting = mutableMapOf<String, QueueFilterMode>()

    // Only the Smart Mix queue holds a list the app chose; the server radio holds none.
    private fun disconnectState() = moveGeneratedModesAside(active, setOf("syncgroup_mix"), awaiting)

    @Test
    fun `a generated list is set aside and every active mode is forgotten`() {
        disconnectState()

        assertThat(awaiting).containsExactly("syncgroup_mix", QueueFilterMode.SMART_GENERATED)
        assertThat(active).isEmpty()
    }

    @Test
    fun `a reconnect with retries keeps what the first state set aside`() {
        disconnectState() // Disconnected
        disconnectState() // Connecting, attempt 1
        disconnectState() // Error
        disconnectState() // Connecting, attempt 2

        assertThat(awaiting).containsExactly("syncgroup_mix", QueueFilterMode.SMART_GENERATED)
    }
}
