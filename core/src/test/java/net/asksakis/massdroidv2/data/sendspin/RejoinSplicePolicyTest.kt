package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.sendspin.RejoinSplicePolicy.Placement
import org.junit.Test

/**
 * Pins when a stream/start after a dropped connection keeps the buffered audio.
 *
 * Keeping it after a skip or a seek made on the server during the drop would play up to 30 s
 * of the wrong audio; flushing it on an untouched stream costs a gap and a jump on every
 * reconnect.
 */
class RejoinSplicePolicyTest {

    private fun report(
        title: String = "Peacewave",
        progressMs: Long = 60_000L,
        atMs: Long = 1_000_000L,
        speed: Int? = 1_000,
        durationMs: Long = 319_000L,
    ) = ServerMetadataPayload(
        timestamp = atMs * 1_000L,
        title = title,
        artist = "Henriku",
        album = "Peacewave",
        progress = MetadataProgressPayload(trackProgress = progressMs, trackDuration = durationMs, playbackSpeed = speed),
    )

    // 14 s after the last report before the drop, an untouched stream stands 14 s further on.
    private val before = listOf(report())
    private val untouched = report(progressMs = 74_000L, atMs = 1_014_000L)

    @Test
    fun `the same track advanced by the time that passed continues the stream`() {
        assertThat(RejoinSplicePolicy.serverStateContinues(before, untouched)).isTrue()
    }

    @Test
    fun `progress within the tolerance still continues`() {
        val late = report(progressMs = 74_000L + RejoinSplicePolicy.PROGRESS_TOLERANCE_MS, atMs = 1_014_000L)
        assertThat(RejoinSplicePolicy.serverStateContinues(before, late)).isTrue()
    }

    @Test
    fun `progress just past the tolerance does not continue the stream`() {
        val seeked = report(progressMs = 74_000L + RejoinSplicePolicy.PROGRESS_TOLERANCE_MS + 100L, atMs = 1_014_000L)
        val paused = report(progressMs = 74_000L - RejoinSplicePolicy.PROGRESS_TOLERANCE_MS - 100L, atMs = 1_014_000L)
        assertThat(RejoinSplicePolicy.serverStateContinues(before, seeked)).isFalse()
        assertThat(RejoinSplicePolicy.serverStateContinues(before, paused)).isFalse()
    }

    @Test
    fun `the progress advances at the reported playback speed`() {
        // 14 s pass on the server clock: at half speed the track moves 7 s, at 1.5x it moves 21 s.
        val half = listOf(report(speed = 500))
        val fast = listOf(report(speed = 1_500))
        assertThat(RejoinSplicePolicy.serverStateContinues(half, report(progressMs = 67_000L, atMs = 1_014_000L, speed = 500)))
            .isTrue()
        assertThat(RejoinSplicePolicy.serverStateContinues(half, report(progressMs = 74_000L, atMs = 1_014_000L, speed = 500)))
            .isFalse()
        assertThat(RejoinSplicePolicy.serverStateContinues(fast, report(progressMs = 81_000L, atMs = 1_014_000L, speed = 1_500)))
            .isTrue()
        assertThat(RejoinSplicePolicy.serverStateContinues(fast, report(progressMs = 74_000L, atMs = 1_014_000L, speed = 1_500)))
            .isFalse()
    }

    @Test
    fun `a report the server stamped before the earlier one still lines up`() {
        // The report kept from before the drop can be a scheduled one stamped in the future.
        val scheduled = listOf(report(progressMs = 80_000L, atMs = 1_020_000L))
        assertThat(RejoinSplicePolicy.serverStateContinues(scheduled, untouched)).isTrue()
        assertThat(RejoinSplicePolicy.serverStateContinues(scheduled, report(progressMs = 90_000L, atMs = 1_014_000L)))
            .isFalse()
    }

    @Test
    fun `the same title by another artist, on another album or of another length is another track`() {
        val otherArtist = untouched.copy(artist = "Someone else")
        val otherAlbum = untouched.copy(album = "Live")
        val otherLength = untouched.copy(progress = untouched.progress?.copy(trackDuration = 320_000L))
        listOf(otherArtist, otherAlbum, otherLength).forEach {
            assertThat(RejoinSplicePolicy.serverStateContinues(before, it)).isFalse()
        }
    }

    @Test
    fun `a seek during the drop does not continue the stream`() {
        val seeked = report(progressMs = 120_000L, atMs = 1_014_000L)
        assertThat(RejoinSplicePolicy.serverStateContinues(before, seeked)).isFalse()
    }

    @Test
    fun `a pause and resume during the drop does not continue the stream`() {
        // Paused for 10 of the 14 s: the progress moved only 4 s.
        val resumed = report(progressMs = 64_000L, atMs = 1_014_000L)
        assertThat(RejoinSplicePolicy.serverStateContinues(before, resumed)).isFalse()
    }

    @Test
    fun `another track does not continue the stream`() {
        val skipped = report(title = "ATM", progressMs = 14_000L, atMs = 1_014_000L)
        assertThat(RejoinSplicePolicy.serverStateContinues(before, skipped)).isFalse()
    }

    @Test
    fun `no report on the new connection never counts as a match`() {
        assertThat(RejoinSplicePolicy.serverStateContinues(before, null)).isFalse()
    }

    @Test
    fun `no report before the drop never counts as a match`() {
        assertThat(RejoinSplicePolicy.serverStateContinues(emptyList(), untouched)).isFalse()
    }

    @Test
    fun `a report without a progress, a time or a speed never counts as a match`() {
        val noProgress = untouched.copy(progress = null)
        val noTime = untouched.copy(timestamp = null)
        val stopped = report(progressMs = 74_000L, atMs = 1_014_000L, speed = 0)
        val noSpeed = report(progressMs = 74_000L, atMs = 1_014_000L, speed = null)
        listOf(noProgress, noTime, stopped, noSpeed).forEach {
            assertThat(RejoinSplicePolicy.serverStateContinues(before, it)).isFalse()
        }
        assertThat(RejoinSplicePolicy.serverStateContinues(listOf(report(speed = 0)), untouched)).isFalse()
    }

    @Test
    fun `the next track's report sent ahead of the drop continues into that track`() {
        // The current track ends at 1_259_000 ms; its successor's report came before the drop.
        val next = report(title = "ATM", progressMs = 0L, atMs = 1_259_000L, durationMs = 419_000L)
        val nowInNext = report(title = "ATM", progressMs = 10_000L, atMs = 1_269_000L, durationMs = 419_000L)
        assertThat(RejoinSplicePolicy.serverStateContinues(listOf(report(), next), nowInNext)).isTrue()
    }

    private fun keeps(
        rejoinVerified: Boolean = true,
        continuation: Boolean = true,
        sameFormat: Boolean = true,
        solo: Boolean = true,
        outputAllowed: Boolean = true,
        holdsAudio: Boolean = true,
    ) = RejoinSplicePolicy.keepsBuffer(rejoinVerified, continuation, sameFormat, solo, outputAllowed, holdsAudio)

    @Test
    fun `a verified rejoin in the same format on the solo engine keeps the buffer`() {
        assertThat(keeps()).isTrue()
    }

    @Test
    fun `a rejoin whose server state did not check out flushes`() {
        assertThat(keeps(rejoinVerified = false)).isFalse()
    }

    @Test
    fun `a format change on the rejoin flushes`() {
        assertThat(keeps(sameFormat = false)).isFalse()
    }

    @Test
    fun `the grouped engine flushes`() {
        assertThat(keeps(solo = false)).isFalse()
    }

    @Test
    fun `a new stream or a held output flushes`() {
        assertThat(keeps(continuation = false)).isFalse()
        assertThat(keeps(outputAllowed = false)).isFalse()
    }

    @Test
    fun `a buffer that ran dry during a drop longer than it flushes`() {
        assertThat(keeps(holdsAudio = false)).isFalse()
    }

    // A FLAC buffer: 96 ms frames, the last one at 100 s, audible from about 83 s.
    private val tail = 100_000_000L
    private val windowStart = 83_000_000L - RejoinSplicePolicy.WINDOW_SLACK_US
    private val frame = 96_000L

    private fun place(frameUs: Long, seenFirst: Boolean = false) =
        RejoinSplicePolicy.place(frameUs, tail, windowStart, frame, seenFirst)

    @Test
    fun `a first frame inside the buffered window is audio the buffer holds`() {
        assertThat(place(84_000_000L)).isEqualTo(Placement.DUPLICATE)
        assertThat(place(tail)).isEqualTo(Placement.DUPLICATE)
    }

    @Test
    fun `the frame after the buffered end continues it`() {
        assertThat(place(tail + frame, seenFirst = true)).isEqualTo(Placement.CONTINUES)
        // On a first frame too, when the server resumes exactly where the buffer ends.
        assertThat(place(tail + frame)).isEqualTo(Placement.CONTINUES)
    }

    @Test
    fun `a frame on another grid that overlaps the last one more than half is a duplicate`() {
        assertThat(place(tail + 30_000L, seenFirst = true)).isEqualTo(Placement.DUPLICATE)
        assertThat(place(tail + 60_000L, seenFirst = true)).isEqualTo(Placement.CONTINUES)
    }

    @Test
    fun `a first frame before the buffered window is outside it`() {
        assertThat(place(windowStart - 1L)).isEqualTo(Placement.OUTSIDE)
        // Later frames only walk up to the tail, so the window does not apply to them.
        assertThat(place(windowStart - 1L, seenFirst = true)).isEqualTo(Placement.DUPLICATE)
    }

    @Test
    fun `a first frame past the buffered end is outside it`() {
        // The 37 s drop: the buffer ran dry and the server resumed 12.8 s later.
        assertThat(place(tail + 12_800_000L)).isEqualTo(Placement.OUTSIDE)
        assertThat(place(tail + frame + FormatSwitchPolicy.CONTINUITY_TOLERANCE_US + 1L)).isEqualTo(Placement.OUTSIDE)
    }

    @Test
    fun `a hole after the walk-up to the tail continues the kept buffer`() {
        // The duplicates up to the tail were already dropped; flushing now would lose the audio
        // from the playhead to the tail, while the solo FIFO only skips the hole.
        assertThat(place(tail + frame + FormatSwitchPolicy.CONTINUITY_TOLERANCE_US + 1L, seenFirst = true))
            .isEqualTo(Placement.CONTINUES)
        assertThat(place(tail + 2_000_000L, seenFirst = true)).isEqualTo(Placement.CONTINUES)
    }
}
