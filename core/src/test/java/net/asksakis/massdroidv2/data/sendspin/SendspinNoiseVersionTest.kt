package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Which MA versions get Sendspin over Noise. aiosendspin speaks Noise from MA 2.10.0. */
class SendspinNoiseVersionTest {

    @Test
    fun `MA 2_10_0 and later use Noise`() {
        for (version in listOf("2.10.0", "2.10.6", "2.10.0b3", "2.11.0", "3.0.0")) {
            assertWithMessage(version).that(SendspinNoiseConnector.supportsNoise(version)).isTrue()
        }
    }

    @Test
    fun `MA 2_9 and older stay on the legacy protocol`() {
        for (version in listOf("2.9.1", "2.9.0b12", "2.1.0", "1.10.0")) {
            assertWithMessage(version).that(SendspinNoiseConnector.supportsNoise(version)).isFalse()
        }
    }

    @Test
    fun `a dev build and an unreadable version count as older`() {
        for (version in listOf("0.0.0", "", "dev", "2.10", "v2.10.0")) {
            assertWithMessage(version).that(SendspinNoiseConnector.supportsNoise(version)).isFalse()
        }
    }
}
