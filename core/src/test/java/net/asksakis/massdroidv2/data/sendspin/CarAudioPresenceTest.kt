package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CarAudioPresenceTest {

    @Test
    fun `a connected sink counts only when the user flagged it as car audio`() {
        val car = setOf("bt:Polestar")
        assertThat(CarAudioPresence.carSinkConnected(listOf("bt:Polestar"), car)).isTrue()
        assertThat(CarAudioPresence.carSinkConnected(listOf("bt:Galaxy Buds2"), car)).isFalse()
        assertThat(CarAudioPresence.carSinkConnected(listOf("bt:Galaxy Buds2", "bt:Polestar"), car)).isTrue()
    }

    @Test
    fun `nothing flagged means never in the car, whatever is connected`() {
        assertThat(CarAudioPresence.carSinkConnected(listOf("bt:Polestar"), emptySet())).isFalse()
        assertThat(CarAudioPresence.carSinkConnected(emptyList(), setOf("bt:Polestar"))).isFalse()
    }
}
