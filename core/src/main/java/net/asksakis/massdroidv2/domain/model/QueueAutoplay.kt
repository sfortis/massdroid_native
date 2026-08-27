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
        const val MODE_GLOBAL = "global"
    }
}
