package net.asksakis.massdroidv2.data.sendspin

import kotlin.math.abs

/**
 * When a codec change in the middle of a stream may keep the audio the phone already holds.
 *
 * The server changes the format of a running stream at a clean boundary: its new encoder
 * starts where the audio already sent ends (aiosendspin `_begin_format_transition`,
 * `resume_at_us=self._sent_end_us`), and it never sends that audio again. The automatic
 * format does this on every Wi-Fi to mobile switch. The engine used to rebuild everything
 * on any codec change, which threw away about 2.5 s of decoded audio: a solo phone jumped
 * ahead by that much and then ran with a few hundred ms of cushion for the rest of the
 * stream, because the server still counted the discarded audio as buffered (2026-10-22).
 */
internal object FormatSwitchPolicy {

    /**
     * How far the first frame of the new format may sit from the end of the old one and
     * still count as the same timeline. Frames are 20 ms (Opus) to about 100 ms (FLAC);
     * a new timeline (a seek, a group change) lands seconds away.
     */
    const val CONTINUITY_TOLERANCE_US = 100_000L

    /**
     * Whether a stream/start may switch the decoder in place. Only a continuation of a
     * stream already playing, in a codec whose output the running native stream can take
     * as it is: the sample rate and the channel count set how the output was opened. A
     * change that arrives while an earlier one still waits in the queue is fine: the
     * server's look-ahead on a solo phone is over 20 s, so a quick Wi-Fi and back switch
     * announces the second format before the first one plays.
     */
    fun keepsBuffer(
        continuation: Boolean,
        configured: Boolean,
        receivedAny: Boolean,
        sameOutputShape: Boolean,
    ): Boolean = continuation && configured && receivedAny && sameOutputShape

    /** Whether the first frame of the new format continues where the old one ended. */
    fun continuesTimeline(expectedResumeUs: Long, firstNewFrameUs: Long): Boolean =
        abs(firstNewFrameUs - expectedResumeUs) <= CONTINUITY_TOLERANCE_US
}
