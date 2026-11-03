package net.asksakis.massdroidv2.data.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A server announces "Music Assistant (<hostname>)", and the add-on's hostname is its
 * container name, so the sign-in list shows a name the user recognises instead.
 */
class DiscoveredServerNameTest {

    @Test
    fun `the hostname inside the announced name is shown`() {
        assertThat(displayNameOf("Music Assistant (mass)", isHomeAssistantAddon = false)).isEqualTo("mass")
    }

    @Test
    fun `the add-on is named as the add-on`() {
        assertThat(displayNameOf("Music Assistant (d5369777-music-assistant)", isHomeAssistantAddon = true))
            .isEqualTo("Home Assistant add-on")
    }

    @Test
    fun `another name is kept, and a missing one falls back`() {
        assertThat(displayNameOf("Living room server", isHomeAssistantAddon = false)).isEqualTo("Living room server")
        assertThat(displayNameOf(null, isHomeAssistantAddon = false)).isEqualTo("Music Assistant")
    }
}
