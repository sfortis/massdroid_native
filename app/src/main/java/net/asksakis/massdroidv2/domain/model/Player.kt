package net.asksakis.massdroidv2.domain.model

data class Player(
    val playerId: String,
    val displayName: String,
    val provider: String = "",
    val type: PlayerType = PlayerType.PLAYER,
    val available: Boolean = true,
    val state: PlaybackState = PlaybackState.IDLE,
    val volumeLevel: Int = 0,
    val volumeMuted: Boolean = false,
    val activeGroup: String? = null,
    val groupChilds: List<String> = emptyList(),
    val currentMedia: NowPlaying? = null,
    val icon: String? = null
)

enum class PlayerType { PLAYER, GROUP, STEREO_PAIR }

enum class PlaybackState { IDLE, PLAYING, PAUSED }

data class FormatOption(val title: String, val value: String)

data class PlayerConfig(
    val name: String = "",
    val crossfadeMode: CrossfadeMode = CrossfadeMode.DISABLED,
    val volumeNormalization: Boolean = false,
    val sendspinFormat: String? = null,
    val sendspinFormatOptions: List<FormatOption> = emptyList()
)

enum class CrossfadeMode(val apiValue: String, val label: String) {
    DISABLED("disabled", "Disabled"),
    STANDARD("standard_crossfade", "Standard"),
    SMART("smart_crossfade", "Smart");

    companion object {
        fun fromApi(value: String): CrossfadeMode =
            entries.find { it.apiValue == value } ?: DISABLED
    }
}

enum class SendspinAudioFormat(val label: String) {
    SMART("Smart"),
    OPUS("Opus"),
    FLAC("FLAC"),
    PCM("PCM");

    fun toApiValue(isWifi: Boolean): String = when (this) {
        SMART -> if (isWifi) "flac:48000:24:2" else "opus:48000:16:2"
        OPUS -> "opus:48000:16:2"
        FLAC -> "flac:48000:24:2"
        PCM -> "pcm:48000:16:2"
    }

    /** Codec name for stream/request-format. */
    fun toCodec(isWifi: Boolean): String = when (this) {
        SMART -> if (isWifi) "flac" else "opus"
        OPUS -> "opus"
        FLAC -> "flac"
        PCM -> "pcm"
    }

    fun toBitDepth(isWifi: Boolean): Int = when (this) {
        SMART -> if (isWifi) 24 else 16
        FLAC -> 24
        else -> 16
    }

    companion object {
        fun fromStored(value: String): SendspinAudioFormat =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: SMART
    }
}

data class NowPlaying(
    val queueId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val imageUrl: String? = null,
    val duration: Double = 0.0,
    val elapsedTime: Double = 0.0,
    val uri: String? = null
)
