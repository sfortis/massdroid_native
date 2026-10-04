package net.asksakis.massdroidv2.data.sendspin

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFineTuneTest {

    @Test
    fun `names stored route keys for the list of calibrated outputs`() {
        assertEquals("Phone speaker", outputNameForRouteKey("speaker"))
        assertEquals("CREATIVE MUVO 2", outputNameForRouteKey("bt:CREATIVE MUVO 2"))
        assertEquals("Wired headphones", outputNameForRouteKey("wired"))
        assertEquals("USB audio", outputNameForRouteKey("usb"))
        assertEquals("Bluetooth device", outputNameForRouteKey("bt:"))
        assertEquals("something", outputNameForRouteKey("something"))
    }
}
