package net.asksakis.massdroidv2.data.party

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The Party plugin copies the server's local address into the join link when nothing public
 * is set up. Such a link does not open for a guest on mobile data, and the screen says so.
 */
class JoinLinkHostTest {

    private fun assertHomeOnly(expected: Boolean, vararg urls: String) = urls.forEach {
        assertWithMessage(it).that(opensOnlyAtHome(it)).isEqualTo(expected)
    }

    @Test
    fun `private IPv4 addresses open only at home`() = assertHomeOnly(
        true,
        "http://192.168.1.20:8095/?join=A",
        "http://10.0.0.2:8095/?join=A",
        "http://172.20.0.5/?join=A",
        "http://169.254.10.1:8095/?join=A",
        "http://100.101.102.103:8095/?join=A"
    )

    @Test
    fun `bare and local names open only at home`() = assertHomeOnly(
        true,
        "http://nas:8095/?join=A",
        "http://localhost:8095/?join=A",
        "http://music.local:8095/?join=A",
        "http://mass.lan/?join=A",
        "http://mass.home.arpa/?join=A",
        "http://mass.internal/?join=A"
    )

    @Test
    fun `local IPv6 addresses open only at home`() = assertHomeOnly(
        true,
        "http://[fd00::1]:8095/?join=A",
        "http://[fe80::1]:8095/?join=A",
        "http://[::1]:8095/?join=A"
    )

    @Test
    fun `public links open from anywhere`() = assertHomeOnly(
        false,
        "https://mass.example.com/?join=A",
        "https://app.music-assistant.io/?remote_id=R1&join=A",
        "https://mass.tail1234.ts.net/?join=A",
        "http://[2001:db8::1]:8095/?join=A",
        "http://8.8.8.8:8095/?join=A"
    )

    @Test
    fun `a link that cannot be read raises no warning`() = assertHomeOnly(false, "not a url", "")
}
