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
    /**
     * The queue/source currently active on this player (MA `active_source`).
     * For a solo player this equals its own id; for a synced group member it is
     * the active GROUP queue id (e.g. `syncgroup_*`). Authoritative for resolving
     * which queue's events/items belong to the selected player, unlike
     * [syncedTo]/[activeGroup] which can point at the sync leader or be null.
     */
    val activeSource: String? = null,
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
    /**
     * Config KEY for the Sendspin format: plain `preferred_sendspin_format` on a Sendspin
     * player, `<sub>||protocol||preferred_sendspin_format` on a universal player, so it is
     * discovered at load and carried here for the save. Null when the player has no such entry.
     */
    val sendspinFormatKey: String? = null,
    /** Generic per-provider output codec (MA `output_codec`, e.g. Sonos flac/mp3/aac/wav). Null when the player has no such entry. */
    val outputCodec: String? = null,
    val outputCodecOptions: List<FormatOption> = emptyList(),
    /**
     * Which source channels this player renders (MA `output_channels`: stereo, left, right,
     * mono). It is a setting of the player, not of a group: a speaker set to `left` plays the
     * left channel whenever it plays, alone or grouped. Null when the player exposes no such
     * entry (a sync group, or a player without play_media).
     */
    val outputChannels: String? = null,
    val outputChannelsOptions: List<FormatOption> = emptyList(),
    /**
     * Config KEY for the output channels. Plain (`output_channels`) on a plain player,
     * protocol-wrapped (`<sub>||protocol||output_channels`) on a universal player, so it is
     * discovered at load and carried here for the save.
     */
    val outputChannelsKey: String? = null,
    /** Server-side static delay in ms for remote sendspin players. Null when not applicable. */
    val sendspinStaticDelayMs: Int? = null,
    /**
     * Config KEY for the static delay. Like the sync-delay key it can be plain
     * (`sendspin_static_delay`) or protocol-wrapped (`<sub>||protocol||sendspin_static_delay`),
     * so it is discovered by suffix at load and carried here for the save.
     */
    val sendspinStaticDelayKey: String? = null,
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

/**
 * The Sendspin format chosen for this phone's own player. AUTOMATIC leaves the
 * server on "automatic" and lets the client decide by network (FLAC on Wi-Fi,
 * Opus on mobile data, see `helloSupportedFormats`); the others are explicit
 * server overrides.
 */
private const val SERVER_FORMAT_AUTOMATIC = "automatic"

enum class SendspinAudioFormat(val label: String, val serverValue: String) {
    AUTOMATIC("Automatic", SERVER_FORMAT_AUTOMATIC),
    OPUS("Opus", "opus:48000:16:2"),
    FLAC("FLAC", "flac:48000:16:2"),
    PCM("PCM", "pcm:48000:16:2");

    companion object {
        /** The server's `preferred_sendspin_format` value that lets the client decide. */
        const val SERVER_AUTOMATIC = SERVER_FORMAT_AUTOMATIC

        /**
         * Stored by releases before Automatic. That "Smart" wrote an explicit FLAC or
         * Opus override to the server on every network change, which is still there.
         */
        const val LEGACY_SMART = "SMART"

        fun fromStored(value: String): SendspinAudioFormat =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: AUTOMATIC
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
