package net.asksakis.massdroidv2.domain.model

/**
 * One selectable value of a queue config entry, exactly as the server describes it.
 *
 * The title is server-provided and already localized, so it is displayed as-is rather
 * than mapped to a string resource. That also means a mode Music Assistant adds later
 * appears in the app without a code change.
 *
 * [disabled] is honoured rather than filtered out: the server marks an option disabled
 * when it cannot work in the current setup (Autoplay's `similar` needs a provider that
 * supplies similar tracks) and explains why in [disabledReason], which is more useful
 * to the listener than an option that silently is not there.
 */
data class QueueConfigOption(
    val value: String,
    val title: String,
    val disabled: Boolean = false,
    val disabledReason: String? = null,
    val description: String? = null
)

/**
 * The Autoplay settings of one queue: how Music Assistant picks tracks once the queue
 * runs out.
 *
 * Autoplay is what used to be called "don't stop the music". Turning it on and off is
 * still a queue command, but from MA 2.10 the strategy behind it is queue
 * configuration, under `autoplay_mode` with `autoplay_playlist` as its companion.
 *
 * [mode] is a plain string, not an enum, because the option list comes from the server
 * complete with titles. The values seen on 2.10 are `auto`, `similar`, `library`,
 * `playlist` and `global`, the last meaning "follow the server-wide default" rather
 * than a strategy of its own.
 *
 * [playlistUri] only applies when [mode] is `playlist`; the server states that
 * dependency itself, which [playlistDependsOnMode] carries so the UI does not hardcode it.
 */
data class AutoplayConfig(
    val mode: String,
    val modeOptions: List<QueueConfigOption> = emptyList(),
    val playlistUri: String? = null,
    val playlistOptions: List<QueueConfigOption> = emptyList(),
    /** The `autoplay_mode` value that makes the playlist choice apply. Null if the server declared no dependency. */
    val playlistDependsOnMode: String? = null,
    /**
     * The server-wide default this queue follows while [mode] is [MODE_GLOBAL], read from
     * the core `player_queues` settings.
     *
     * Carried so "Global" can say what it currently resolves to. On its own that option
     * reads as "follow the default" without ever saying what the default is, which is
     * exactly the thing a listener wants to know before choosing it.
     */
    val globalMode: String? = null
) {
    /** True when the playlist choice is relevant for the currently selected [mode]. */
    val playlistApplies: Boolean
        get() = playlistDependsOnMode != null && mode == playlistDependsOnMode

    /** The option describing [mode], when the server offered one. */
    val selectedModeOption: QueueConfigOption?
        get() = modeOptions.firstOrNull { it.value == mode }

    /**
     * Title of the source that [MODE_GLOBAL] currently resolves to, for display next to
     * that option. Null when the global value is unknown or is not one of the offered
     * sources.
     */
    val globalModeTitle: String?
        get() = globalMode
            ?.takeIf { it != MODE_GLOBAL }
            ?.let { global -> modeOptions.firstOrNull { it.value == global }?.title }

    companion object {
        /** Config key of the mode select. */
        const val KEY_MODE = "autoplay_mode"

        /** Config key of the playlist companion. */
        const val KEY_PLAYLIST = "autoplay_playlist"

        /** The per-queue value meaning "follow the server-wide default" rather than a source of its own. */
        const val MODE_GLOBAL = QueueChoice.VALUE_GLOBAL
    }
}

/**
 * One string-valued config entry of a queue: what is chosen now, and what may be chosen.
 *
 * Crossfade mode, volume normalization and smart shuffle all have this shape. Each is a
 * select whose options the server describes, and each offers `global`, meaning the queue
 * follows the server-wide default instead of holding a value of its own.
 *
 * The value stays a plain string for the same reason [AutoplayConfig.mode] does: the
 * options arrive from the server with their titles, so a value Music Assistant adds
 * later shows up without a code change.
 */
data class QueueChoice(
    val key: String,
    val value: String,
    val options: List<QueueConfigOption> = emptyList(),
    /**
     * The server-wide value this queue follows while [value] is [VALUE_GLOBAL], read from
     * the core `player_queues` settings, so the UI can say what "Global" resolves to.
     */
    val globalValue: String? = null
) {
    /** The option describing [value], when the server offered one. */
    val selectedOption: QueueConfigOption?
        get() = options.firstOrNull { it.value == value }

    /** Whether this queue currently follows the server-wide default. */
    val followsGlobal: Boolean
        get() = value == VALUE_GLOBAL

    /**
     * Title of what [VALUE_GLOBAL] currently resolves to, for display next to that
     * option. Null when the server-wide value is unknown or is not one of the options.
     */
    val globalTitle: String?
        get() = globalValue
            ?.takeIf { it != VALUE_GLOBAL }
            ?.let { global -> options.firstOrNull { it.value == global }?.title }

    companion object {
        /** The per-queue value meaning "follow the server-wide default". */
        const val VALUE_GLOBAL = "global"

        /** Which kind of crossfade runs, while crossfade is on. */
        const val KEY_CROSSFADE_MODE = "crossfade_mode"

        /** Whether playback is levelled to a common loudness (EBU R128). */
        const val KEY_VOLUME_NORMALIZATION = "volume_normalization"

        /** Whether shuffle spreads artists and albums out instead of ordering at random. */
        const val KEY_SMART_SHUFFLE = "smart_shuffle_enabled"
    }
}

/**
 * The configuration of one queue, as of MA 2.10.
 *
 * These settings used to live on the player. Music Assistant 2.10 moved them to the
 * queue, so the app reads them from `config/player_queues/get` in one call and the
 * player config no longer carries them at all.
 *
 * Every field is nullable because an older server, or an account that may not read queue
 * config, simply does not supply them, and each control hides itself rather than showing
 * a default that is not what the server would do.
 *
 * Crossfade on and off is deliberately absent: it is not configuration but a property of
 * the queue itself, alongside shuffle and repeat, changed with `player_queues/crossfade`.
 */
data class QueueSettings(
    val autoplay: AutoplayConfig? = null,
    val crossfadeMode: QueueChoice? = null,
    val volumeNormalization: QueueChoice? = null,
    val smartShuffle: QueueChoice? = null
) {
    /**
     * The same settings with [choice] in place of the entry it belongs to, so a caller
     * showing a new selection does not have to know which field holds it.
     */
    fun with(choice: QueueChoice): QueueSettings = when (choice.key) {
        QueueChoice.KEY_CROSSFADE_MODE -> copy(crossfadeMode = choice)
        QueueChoice.KEY_VOLUME_NORMALIZATION -> copy(volumeNormalization = choice)
        QueueChoice.KEY_SMART_SHUFFLE -> copy(smartShuffle = choice)
        else -> this
    }
}
