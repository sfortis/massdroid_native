package net.asksakis.massdroidv2.domain.model

data class QueueState(
    val queueId: String,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val elapsedTime: Double = 0.0,
    val currentItem: QueueItem? = null,
    val currentIndex: Int = 0,
    /**
     * Highest index the player already holds. Items at or below it cannot be removed or
     * reordered server side, so anything that wants to change the queue has to leave them
     * alone. Null when the server did not report one.
     */
    val indexInBuffer: Int? = null,
    /**
     * What this queue was filled from, when it was filled from one thing. It answers
     * "where is this playing from", which nothing else in the queue state does: the items
     * only know their own album. Null when the server reports no source, which is what a
     * queue built from a list of tracks looks like.
     */
    val source: QueueSource? = null,
    /** Total item count of the whole queue (server-side), not just the fetched page. */
    val totalItems: Int = 0,
    val autoplayEnabled: Boolean = false,
    /** Whether crossfade is on. A queue property from MA 2.10; before that, player config. */
    val crossfadeEnabled: Boolean = false
)

data class QueueItem(
    val queueItemId: String,
    val name: String = "",
    val duration: Double = 0.0,
    val track: Track? = null,
    val imageUrl: String? = null,
    val audioFormat: AudioFormatInfo? = null
)

data class AudioFormatInfo(
    val contentType: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitRate: Int? = null,
    val channels: Int? = null
)

enum class RepeatMode(val apiValue: String) {
    OFF("off"),
    ONE("one"),
    ALL("all");

    companion object {
        fun fromApi(value: String): RepeatMode = entries.find { it.apiValue == value } ?: OFF
    }
}

/**
 * The playlist, album or other container a queue was started from.
 *
 * Kept small on purpose. Callers want to name it, and to play it again, and neither needs
 * the rest of a media item.
 */
data class QueueSource(
    val uri: String,
    val name: String,
    val mediaType: MediaType
)
