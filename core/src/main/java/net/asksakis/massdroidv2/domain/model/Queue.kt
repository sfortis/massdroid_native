package net.asksakis.massdroidv2.domain.model

data class QueueState(
    val queueId: String,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val elapsedTime: Double = 0.0,
    val currentItem: QueueItem? = null,
    val currentIndex: Int = 0,
    val dontStopTheMusicEnabled: Boolean = false
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
