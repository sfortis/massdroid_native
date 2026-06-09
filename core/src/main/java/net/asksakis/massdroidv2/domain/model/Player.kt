package net.asksakis.massdroidv2.domain.model

data class Player(
    val playerId: String,
    val displayName: String,
    val provider: String = "",
    val type: PlayerType = PlayerType.PLAYER,
    val available: Boolean = true,
    val state: PlaybackState = PlaybackState.IDLE,
    val powered: Boolean = true,
    val volumeLevel: Int = 0,
    /** Average volume of children for group players; null when no children support volume. */
    val groupVolume: Int? = null,
    val volumeMuted: Boolean = false,
    val activeGroup: String? = null,
    /** Player this one is currently synced to at protocol level, if any. */
    val syncedTo: String? = null,
    val groupChilds: List<String> = emptyList(),
    /** Permanent members set at group creation; cannot be removed via set_members. */
    val staticGroupMembers: List<String> = emptyList(),
    val supportedFeatures: Set<String> = emptySet(),
    val canGroupWith: List<String> = emptyList(),
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
    val sendspinFormatOptions: List<FormatOption> = emptyList(),
    /** Generic per-provider output codec (MA `output_codec`, e.g. Sonos flac/mp3/aac/wav). Null when the player has no such entry. */
    val outputCodec: String? = null,
    val outputCodecOptions: List<FormatOption> = emptyList(),
    /** Server-side static delay in ms for remote sendspin players. Null when not applicable. */
    val sendspinStaticDelayMs: Int? = null,
    /**
     * Server-side per-player Sendspin sync delay (the MA "Sync delay (ms)"
     * config, range -1000..1000, positive = play later). The config KEY varies
     * by player (e.g. `sendspin_sync_delay` or `<sub>||protocol||sendspin_sync_delay`),
     * so it is discovered at load and carried here for the save. Null when the
     * player does not expose it (e.g. our own client-side player).
     */
    val sendspinSyncDelayKey: String? = null,
    val sendspinSyncDelayMs: Int? = null,
    /** Server-advertised default for the sync delay; Reset restores this (per speaker). */
    val sendspinSyncDelayDefault: Int? = null,
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
        // Stable Android sync path: local/Wi-Fi playback uses FLAC 48/16.
        // Higher-rate/24-bit streams add Android decoder and output-conversion
        // variables while the engine still writes PCM16 to AudioTrack.
        SMART -> if (isWifi) "flac:48000:16:2" else "opus:48000:16:2"
        OPUS -> "opus:48000:16:2"
        FLAC -> "flac:48000:16:2"
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
        SMART -> 16
        FLAC -> 16
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
