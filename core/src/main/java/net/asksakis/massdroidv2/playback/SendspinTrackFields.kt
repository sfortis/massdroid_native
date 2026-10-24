package net.asksakis.massdroidv2.playback

import net.asksakis.massdroidv2.data.sendspin.SendspinWire
import net.asksakis.massdroidv2.data.sendspin.ServerMetadataPayload

/**
 * The track fields the notification and the MediaSession show for the Sendspin player, and how a
 * Sendspin metadata update changes them.
 *
 * The legacy wire sends only what changed, so a field the update leaves out keeps its value. On
 * the rc1 wire the metadata object is the whole state, and a field it leaves out is unset: a radio
 * stream after a track carries no album. Keeping the previous value there showed the previous
 * track's artist, album and cover on the notification and in the car. The title is kept on both
 * wires, because the notification always needs one and MA's own metadata names the item.
 */
internal data class SendspinTrackFields(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUrl: String?,
) {
    fun updatedBy(metadata: ServerMetadataPayload, wire: SendspinWire): SendspinTrackFields {
        val replaces = wire == SendspinWire.RC1
        return SendspinTrackFields(
            title = metadata.title?.takeIf { it.isNotBlank() } ?: title,
            artist = metadata.artist ?: if (replaces) "" else artist,
            album = metadata.album ?: if (replaces) "" else album,
            durationMs = metadata.progress?.trackDuration ?: if (replaces) 0L else durationMs,
            artUrl = metadata.artworkUrl ?: if (replaces) null else artUrl,
        )
    }
}
