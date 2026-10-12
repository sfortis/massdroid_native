package net.asksakis.massdroidv2.data.sendspin

import net.asksakis.massdroidv2.domain.model.IntSetting
import net.asksakis.massdroidv2.domain.model.OutputProtocol
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which setting moves a remote member of a Sendspin group, from the member's outputs and
 * config as MA 2.10.6 reports them for the owner's speakers (2026-10-11).
 */
class MemberDelayResolverTest {

    private fun player(vararg outputs: OutputProtocol) = Player(playerId = "p1", displayName = "Speaker", outputProtocols = outputs.toList())

    /** A Sonos S1 joins through Sendspin derived from its AirPlay output. */
    private val sonos = player(
        OutputProtocol("native", "sonos_s1"),
        OutputProtocol("apf0", "airplay"),
        OutputProtocol("spb_f0", "sendspin", derivedFrom = "apf0"),
        OutputProtocol("uuid:RINCON_MR", "dlna"),
    )

    /** A Chromecast joins through Sendspin derived from its native Cast output. */
    private val cast = player(
        OutputProtocol("native", "chromecast"),
        OutputProtocol("spb_ac", "sendspin", derivedFrom = "native"),
    )

    @Test
    fun `Sendspin over AirPlay is moved by the AirPlay sync adjust and restarts playback`() {
        val config = PlayerConfig(
            syncAdjust = mapOf("apf0" to IntSetting("apf0||protocol||sync_adjust", 40)),
            sendspinDelayWaitsForPlayback = true,
        )

        assertEquals(
            MemberDelay.Adjustable("apf0||protocol||sync_adjust", DelayControl.Signed(40, -500, 500), restartsPlayback = true),
            MemberDelayResolver.resolve(sonos, config)
        )
    }

    @Test
    fun `a native AirPlay player's own sync adjust is the bare entry`() {
        val airplay = player(OutputProtocol("native", "airplay"), OutputProtocol("spb_x", "sendspin", derivedFrom = "native"))
        val config = PlayerConfig(syncAdjust = mapOf("native" to IntSetting("sync_adjust", -20)))

        val delay = MemberDelayResolver.resolve(airplay, config) as MemberDelay.Adjustable

        assertEquals("sync_adjust", delay.key)
        assertEquals(DelayControl.Signed(-20, -500, 500), delay.control)
    }

    @Test
    fun `a playing Sendspin over Cast output is moved live by its static delay`() {
        val config = PlayerConfig(sendspinStaticDelayKey = "spb_ac||protocol||sendspin_static_delay", sendspinStaticDelayMs = 415)

        assertEquals(
            MemberDelay.Adjustable("spb_ac||protocol||sendspin_static_delay", DelayControl.Static(415), restartsPlayback = false),
            MemberDelayResolver.resolve(cast, config)
        )
    }

    @Test
    fun `an idle Sendspin over Cast output waits for playback`() {
        assertEquals(
            MemberDelay.WaitsForPlayback,
            MemberDelayResolver.resolve(cast, PlayerConfig(sendspinDelayWaitsForPlayback = true))
        )
    }

    @Test
    fun `a sync adjust on an output Sendspin does not run on is ignored`() {
        // The Cast device's Sendspin is derived from Cast, so an AirPlay sync_adjust does not apply.
        val both = player(
            OutputProtocol("native", "chromecast"),
            OutputProtocol("ap1", "airplay"),
            OutputProtocol("spb_ac", "sendspin", derivedFrom = "native"),
        )
        val config = PlayerConfig(
            syncAdjust = mapOf("ap1" to IntSetting("ap1||protocol||sync_adjust", 0)),
            sendspinDelayWaitsForPlayback = true,
        )

        assertEquals(MemberDelay.WaitsForPlayback, MemberDelayResolver.resolve(both, config))
    }

    @Test
    fun `the signed sync delay wins over the static delay`() {
        val config = PlayerConfig(
            sendspinSyncDelayKey = "sendspin_sync_delay",
            sendspinSyncDelayMs = -30,
            sendspinStaticDelayKey = "sendspin_static_delay",
            sendspinStaticDelayMs = 100,
        )

        assertEquals(
            MemberDelay.Adjustable("sendspin_sync_delay", DelayControl.Signed(-30), restartsPlayback = false),
            MemberDelayResolver.resolve(player(), config)
        )
    }

    @Test
    fun `a client without any delay entry sets it on the device`() {
        assertEquals(MemberDelay.OnDevice, MemberDelayResolver.resolve(player(), PlayerConfig()))
        assertEquals(MemberDelay.OnDevice, MemberDelayResolver.resolve(player(), null))
    }
}
