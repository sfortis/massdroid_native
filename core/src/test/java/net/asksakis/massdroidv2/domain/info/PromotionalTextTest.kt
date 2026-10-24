package net.asksakis.massdroidv2.domain.info

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins which provider descriptions count as contact details instead of an about text. */
class PromotionalTextTest {

    @Test
    fun `the Deezer bio with a phone number is caught`() {
        assertThat(PromotionalText.hasContactDetails("Queen of Texas\n// text me +1 (858) 386-4193 for updates")).isTrue()
    }

    @Test
    fun `phone numbers in common formats are caught`() {
        assertThat(PromotionalText.hasContactDetails("Bookings: +30 210 1234567")).isTrue()
        assertThat(PromotionalText.hasContactDetails("call (858) 386-4193")).isTrue()
        assertThat(PromotionalText.hasContactDetails("call 858-386-4193 now")).isTrue()
        assertThat(PromotionalText.hasContactDetails("call 858.386.4193 now")).isTrue()
    }

    @Test
    fun `an email address is caught`() {
        assertThat(PromotionalText.hasContactDetails("booking: band.mgmt+eu@example-label.co.uk")).isTrue()
    }

    @Test
    fun `a real bio with years, counts and a link is kept`() {
        val bio = "Warpaint is an American indie rock band formed in Los Angeles in 2004. Their albums " +
            "of 2010, 2014 and 2016 sold over 1,000,000 copies, with tours in 2011-2012 and 2014 2015 " +
            "2016. <a href=\"https://www.last.fm/music/Warpaint\">Read more on Last.fm</a>"

        assertThat(PromotionalText.hasContactDetails(bio)).isFalse()
    }

    @Test
    fun `a catalogue number or a date range is not a phone number`() {
        assertThat(PromotionalText.hasContactDetails("Released as RTRADCD-123 on 2014-01-17")).isFalse()
        assertThat(PromotionalText.hasContactDetails("Active 1999-2005, reunited 2019")).isFalse()
    }
}
