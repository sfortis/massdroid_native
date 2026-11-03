package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.sendspin.isRealMacAddress
import net.asksakis.massdroidv2.domain.model.PlaybackState
import org.junit.Test

/**
 * Playback that starts by itself: a Reddit user and the owner saw music start after a Bluetooth
 * connect or a network change, hours after a deliberate pause (2026-11-01).
 */
class AutoResumePolicyTest {

    @Test
    fun `the device whose loss paused the music resumes it`() {
        assertThat(AutoResumePolicy.bluetoothConnectResumes("mac:AA:BB:CC:DD:EE:FF", "mac:AA:BB:CC:DD:EE:FF")).isTrue()
    }

    @Test
    fun `another device, or a connect after a deliberate pause, does not`() {
        assertThat(AutoResumePolicy.bluetoothConnectResumes("mac:AA:BB:CC:DD:EE:FF", "mac:11:22:33:44:55:66")).isFalse()
        assertThat(AutoResumePolicy.bluetoothConnectResumes(null, "mac:AA:BB:CC:DD:EE:FF")).isFalse()
    }

    @Test
    fun `a device that cannot be identified resumes nothing`() {
        assertThat(AutoResumePolicy.bluetoothConnectResumes(null, null)).isFalse()
        assertThat(AutoResumePolicy.bluetoothConnectResumes("bt:MINI45864", null)).isFalse()
    }

    @Test
    fun `a reconnect resumes what the network cut`() {
        assertThat(AutoResumePolicy.reconnectResumes(true, true, PlaybackState.PLAYING)).isTrue()
    }

    @Test
    fun `a reconnect after a pause made elsewhere stays paused`() {
        assertThat(AutoResumePolicy.reconnectResumes(true, true, PlaybackState.PAUSED)).isFalse()
        assertThat(AutoResumePolicy.reconnectResumes(true, true, PlaybackState.IDLE)).isFalse()
        assertThat(AutoResumePolicy.reconnectResumes(true, true, null)).isFalse()
    }

    @Test
    fun `a reconnect after a pause here stays paused even if the server never heard it`() {
        assertThat(AutoResumePolicy.reconnectResumes(false, true, PlaybackState.PLAYING)).isFalse()
        assertThat(AutoResumePolicy.reconnectResumes(true, false, PlaybackState.PLAYING)).isFalse()
    }

    @Test
    fun `only a real MAC address identifies a device`() {
        assertThat(isRealMacAddress("AC:12:2F:00:9B:41")).isTrue()
        assertThat(isRealMacAddress("02:00:00:00:00:00")).isFalse()
        assertThat(isRealMacAddress("00:00:00:00:00:00")).isFalse()
        assertThat(isRealMacAddress("")).isFalse()
        assertThat(isRealMacAddress("MINI45864")).isFalse()
    }

    @Test
    fun `a MAC masked for an app without the Bluetooth permission is not an identity`() {
        // Android hides the first four bytes from an app without BLUETOOTH_CONNECT; the device
        // is then remembered by its name instead.
        assertThat(isRealMacAddress("XX:XX:XX:XX:9B:41")).isFalse()
    }

    @Test
    fun `a route loss confirmed while the listener still wanted playback leaves a ticket`() {
        assertThat(AutoResumePolicy.ticketAfterRouteLoss("mac:AA:BB:CC:DD:EE:FF", true, pausesAtHold = 3, pausesAtDeadline = 3))
            .isEqualTo("mac:AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `a pause pressed during the route-loss hold leaves no ticket`() {
        assertThat(AutoResumePolicy.ticketAfterRouteLoss("mac:AA:BB:CC:DD:EE:FF", false, pausesAtHold = 3, pausesAtDeadline = 4))
            .isNull()
    }

    @Test
    fun `a pause pressed while the transport was not ready leaves no ticket, though the intent stayed`() {
        // That pause returns before it clears the intent; only the pause count moved on.
        assertThat(AutoResumePolicy.ticketAfterRouteLoss("mac:AA:BB:CC:DD:EE:FF", true, pausesAtHold = 3, pausesAtDeadline = 4))
            .isNull()
    }

    @Test
    fun `a route loss of a device that cannot be identified leaves no ticket`() {
        assertThat(AutoResumePolicy.ticketAfterRouteLoss(null, true, pausesAtHold = 0, pausesAtDeadline = 0)).isNull()
    }

    private val hold = AutoResumePolicy.POST_DROP_HOLD_MS

    private fun ServerPlaybackRecord.report(transportUp: Boolean, state: PlaybackState?, atMs: Long) =
        AutoResumePolicy.recordServerReport(this, transportUp, state, atMs)

    /** Playing with the Sendspin connection up, as recorded at [atMs]. */
    private fun playingAt(atMs: Long) = ServerPlaybackRecord().report(true, PlaybackState.PLAYING, atMs)

    @Test
    fun `reports made while the Sendspin connection is up are recorded`() {
        val playing = playingAt(0)
        assertThat(playing.state).isEqualTo(PlaybackState.PLAYING)
        assertThat(playing.report(true, PlaybackState.PAUSED, 1_000).state).isEqualTo(PlaybackState.PAUSED)
    }

    @Test
    fun `reports made while the Sendspin connection is down are ignored`() {
        val dropped = playingAt(0).report(false, PlaybackState.IDLE, 1_000)
        assertThat(dropped.state).isEqualTo(PlaybackState.PLAYING)
    }

    @Test
    fun `a missing or unavailable player changes nothing`() {
        assertThat(playingAt(0).report(true, null, 1_000).state).isEqualTo(PlaybackState.PLAYING)
    }

    @Test
    fun `the IDLE that follows a drop does not replace the last state`() {
        // Wi-Fi to mobile: the transport drops, Sendspin registers again and MA reports the IDLE
        // its own stop caused, then the MA connection drops a second time.
        val record = playingAt(0)
            .report(false, null, 1_000)
            .report(true, PlaybackState.IDLE, 3_000)
        assertThat(record.state).isEqualTo(PlaybackState.PLAYING)
        assertThat(AutoResumePolicy.reconnectResumes(true, true, record.state)).isTrue()
    }

    @Test
    fun `the hold ends a minute after the connection came back`() {
        // Only the Sendspin connection dropped; the MA one stayed up and no reconnect read the
        // record. A pause made later must count, or a network change hours later would resume.
        val back = playingAt(0)
            .report(false, null, 1_000)
            .report(true, PlaybackState.IDLE, 2_000)
        val later = back.report(true, PlaybackState.PAUSED, 2_000 + hold)
        assertThat(later.state).isEqualTo(PlaybackState.PAUSED)
        assertThat(AutoResumePolicy.reconnectResumes(true, true, later.state)).isFalse()
    }

    @Test
    fun `the hold counts from the return of the connection, however long it was down`() {
        val back = playingAt(0)
            .report(false, null, 1_000)
            .report(true, PlaybackState.IDLE, 10 * hold)
        assertThat(back.report(true, PlaybackState.IDLE, 10 * hold + hold - 1).state)
            .isEqualTo(PlaybackState.PLAYING)
    }

    @Test
    fun `a PLAYING report after a drop ends the hold`() {
        val record = playingAt(0)
            .report(false, null, 1_000)
            .report(true, PlaybackState.PLAYING, 2_000)
            .report(true, PlaybackState.PAUSED, 3_000)
        assertThat(record.state).isEqualTo(PlaybackState.PAUSED)
    }

    @Test
    fun `after a reconnect read the record, a pause made elsewhere counts again`() {
        val dropped = playingAt(0)
            .report(false, null, 1_000)
            .report(true, PlaybackState.IDLE, 2_000)
        val read = AutoResumePolicy.consumedByReconnect(dropped, 2_500)
        assertThat(read.report(true, PlaybackState.PAUSED, 3_000).state).isEqualTo(PlaybackState.PAUSED)
    }

    @Test
    fun `a report held back counts once the window is over, with no report after it`() {
        // Only the Sendspin connection dropped, MA reported the re-registered player IDLE inside
        // the window, and the players list stayed quiet all night. A reconnect hours later must
        // read the IDLE, not the old PLAYING.
        val back = playingAt(0)
            .report(false, null, 1_000)
            .report(true, PlaybackState.IDLE, 2_000)
        assertThat(AutoResumePolicy.stateAt(back, 2_000 + hold - 1)).isEqualTo(PlaybackState.PLAYING)
        assertThat(AutoResumePolicy.stateAt(back, 2_000 + hold)).isEqualTo(PlaybackState.IDLE)
        val nightLater = 2_000 + 8 * 60 * 60 * 1_000L
        assertThat(AutoResumePolicy.reconnectResumes(true, true, AutoResumePolicy.stateAt(back, nightLater))).isFalse()
    }

    @Test
    fun `a reconnect after the window reads the report held back`() {
        val back = playingAt(0)
            .report(false, null, 1_000)
            .report(true, PlaybackState.PAUSED, 2_000)
        val read = AutoResumePolicy.consumedByReconnect(back, 2_000 + hold)
        assertThat(read.state).isEqualTo(PlaybackState.PAUSED)
    }

    @Test
    fun `while the connection is down the window does not run out`() {
        val down = playingAt(0).report(false, null, 1_000)
        assertThat(AutoResumePolicy.stateAt(down, 1_000 + 10 * hold)).isEqualTo(PlaybackState.PLAYING)
    }

    @Test
    fun `a second drop inside the window starts a new hold`() {
        val record = playingAt(0)
            .report(false, null, 1_000)
            .report(true, PlaybackState.IDLE, 2_000)
            .report(false, null, 3_000)
            .report(true, PlaybackState.IDLE, 4_000)
        // Counted from the second return, not the first.
        assertThat(AutoResumePolicy.stateAt(record, 2_000 + hold)).isEqualTo(PlaybackState.PLAYING)
        assertThat(AutoResumePolicy.reconnectResumes(true, true, AutoResumePolicy.stateAt(record, 4_000 + hold - 1))).isTrue()
        assertThat(AutoResumePolicy.stateAt(record, 4_000 + hold)).isEqualTo(PlaybackState.IDLE)
    }

    @Test
    fun `the first reports after start count, although the connection starts down`() {
        // start() sees the connection down first. Nothing PLAYING is recorded yet, so there is
        // nothing to hold and the first PAUSED is taken.
        val record = ServerPlaybackRecord()
            .report(false, null, 0)
            .report(true, PlaybackState.PAUSED, 1_000)
        assertThat(record.state).isEqualTo(PlaybackState.PAUSED)
    }
}
