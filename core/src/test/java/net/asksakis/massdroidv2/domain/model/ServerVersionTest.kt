package net.asksakis.massdroidv2.domain.model

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** The one reading of a Music Assistant version that every version gate in the app shares. */
class ServerVersionTest {

    @Test
    fun `the same and later versions pass`() {
        for (version in listOf("2.10.7", "2.10.10", "2.11.0", "2.11.0b4", "3.0.0")) {
            assertWithMessage(version).that(isServerVersionAtLeast(version, 2, 10, 7)).isTrue()
        }
    }

    @Test
    fun `earlier versions do not, compared as numbers rather than text`() {
        for (version in listOf("2.10.6", "2.9.13", "1.99.99", "2.10.0b3")) {
            assertWithMessage(version).that(isServerVersionAtLeast(version, 2, 10, 7)).isFalse()
        }
    }

    @Test
    fun `a dev build and an unreadable version count as older`() {
        for (version in listOf("0.0.0", "", "dev", "2.10", "v2.10.7", null)) {
            assertWithMessage("$version").that(isServerVersionAtLeast(version, 2, 10)).isFalse()
        }
    }

    @Test
    fun `the patch defaults to zero`() {
        assertWithMessage("2.10.0").that(isServerVersionAtLeast("2.10.0", 2, 10)).isTrue()
        assertWithMessage("2.9.99").that(isServerVersionAtLeast("2.9.99", 2, 10)).isFalse()
    }
}
