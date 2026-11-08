package net.asksakis.massdroidv2.data.sendspin

import kotlin.math.abs

/**
 * When a stream/start that follows a dropped connection may keep the audio the phone holds.
 *
 * The phone plays through a network drop from its buffer, about 30 s at most. When the socket
 * comes back, aiosendspin rejoins the player at the playhead of the running stream
 * (`PushStream.on_role_join`) and sends a new stream/start, followed by audio the phone mostly
 * holds already. The engine used to flush everything at that start, which cost 0.2 to 0.5 s of
 * silence and a jump of up to 1 s on every reconnect, with up to 28 s of matching audio thrown
 * away (measured on the emulator, 2026-11-04).
 *
 * Keeping the buffer is right only while the server is still playing the same audio at the same
 * place. A skip, a seek or a pause and resume made on the server during the drop starts a new
 * stream whose timestamps can fall inside the buffered range all the same, so the timestamps
 * alone cannot tell. The server's metadata can: before the drop it said which track played and
 * where it stood at a server time, and on the new connection it says that again. If the two
 * describe one track advancing at the playback speed, nothing moved. Anything else, or no
 * answer on the new connection before its stream/start, flushes as before.
 */
internal object RejoinSplicePolicy {

    /**
     * How far the progress the server reports on the new connection may sit from where the
     * progress reported before the drop says it should be. MA reports the wall-clock time since
     * the stream began, so an untouched stream lands within a few hundred ms; a seek moves it by
     * seconds.
     */
    const val PROGRESS_TOLERANCE_MS = 1_500L

    /** How far before the audible end of the buffer the server may restart and still count. */
    const val WINDOW_SLACK_US = 2_000_000L

    /** Normal speed in the metadata's `playback_speed`, which is the speed times 1000. */
    private const val NORMAL_SPEED = 1_000L

    /**
     * Whether the server's metadata on the new connection continues the stream the buffer holds.
     * [before] is what the dropped connection reported, newest last, which can include the next
     * track's update sent ahead of time; [after] is the latest report on the new connection
     * before its stream/start, null when none came.
     */
    fun serverStateContinues(before: List<ServerMetadataPayload>, after: ServerMetadataPayload?): Boolean {
        val now = after?.let(::positionOf) ?: return false
        return before.any { earlier ->
            val then = positionOf(earlier) ?: return@any false
            sameTrack(earlier, after) &&
                abs(now.progressMs - then.progressAt(now.timestampUs)) <= PROGRESS_TOLERANCE_MS
        }
    }

    /**
     * Whether a stream/start may keep the buffer. Only a continuation after a dropped
     * connection whose server state checked out, in the format the buffer is in, on the solo
     * engine, while the output may play and the buffer still holds audio: a drop longer than the
     * buffer leaves nothing to splice onto.
     *
     * Solo only: the grouped engine schedules every sample on the server clock, and the time
     * sync restarts with the connection. Today that relock happens under the startup mute;
     * kept audio would play through it and let the correction be heard.
     */
    fun keepsBuffer(
        rejoinVerified: Boolean,
        continuation: Boolean,
        sameFormat: Boolean,
        solo: Boolean,
        outputAllowed: Boolean,
        holdsAudio: Boolean,
    ): Boolean = rejoinVerified && continuation && sameFormat && solo && outputAllowed && holdsAudio

    /** Where an incoming frame of a rejoined stream falls against the buffer it splices onto. */
    enum class Placement {
        /** Audio the buffer already holds: drop it and keep waiting. */
        DUPLICATE,

        /** The first frame after the buffer's end, or after a walk-up past it: the splice is done, enqueue it. */
        CONTINUES,

        /** A first frame outside the buffered window, before it or past its end: flush as before. */
        OUTSIDE,
    }

    /**
     * Places [frameUs] against a buffer that ends with a frame at [tailUs] and starts being
     * audible around [windowStartUs]. [seenFirst] is false for the first frame of the new
     * stream, which alone decides whether the new stream belongs to the buffer: it has to start
     * inside the buffered window, no later than right after its end.
     *
     * Once the first frame fell inside the buffer, the frames that follow walk up to the tail,
     * and the first one past it continues the buffer even across a hole. By then the buffer has
     * been kept and the new stream's copy of it dropped, so a flush would throw away the audio
     * from the playhead to the tail, up to about 30 s, while the solo engine, a plain FIFO,
     * plays a hole as a skip of its own length only.
     *
     * A frame that starts within half a frame after the tail overlaps the last buffered frame
     * more than it continues it, so it counts as a duplicate. The new frames can sit on another
     * grid when the server re-encodes for the rejoin, and this keeps the overlap or the hole at
     * the splice under half a frame.
     */
    fun place(
        frameUs: Long,
        tailUs: Long,
        windowStartUs: Long,
        frameDurationUs: Long,
        seenFirst: Boolean,
    ): Placement = when {
        !seenFirst && frameUs < windowStartUs -> Placement.OUTSIDE
        frameUs < tailUs + frameDurationUs / 2 -> Placement.DUPLICATE
        seenFirst -> Placement.CONTINUES
        frameUs <= tailUs + frameDurationUs + FormatSwitchPolicy.CONTINUITY_TOLERANCE_US -> Placement.CONTINUES
        else -> Placement.OUTSIDE
    }

    private fun sameTrack(a: ServerMetadataPayload, b: ServerMetadataPayload): Boolean =
        a.title == b.title &&
            a.artist == b.artist &&
            a.album == b.album &&
            a.progress?.trackDuration == b.progress?.trackDuration

    private class Position(val timestampUs: Long, val progressMs: Long, val speed: Long) {
        fun progressAt(atUs: Long): Long = progressMs + (atUs - timestampUs) / 1_000L * speed / NORMAL_SPEED
    }

    /** The position a report states, or null when it lacks a part or says playback is stopped. */
    private fun positionOf(metadata: ServerMetadataPayload): Position? {
        val progress = metadata.progress ?: return null
        val timestampUs = metadata.timestamp ?: return null
        val progressMs = progress.trackProgress ?: return null
        val speed = progress.playbackSpeed?.toLong()?.takeIf { it > 0L } ?: return null
        return Position(timestampUs, progressMs, speed)
    }
}
