package net.asksakis.massdroidv2.data.websocket

/**
 * Commands that must never be sent a second time after a timeout.
 *
 * A timeout means the answer did not arrive, not that the command did not run. When a
 * slow server merely answers late, a retry applies the command twice, so retrying is
 * only safe where doing something twice has the same effect as doing it once.
 *
 * Most writes qualify: setting a volume, a repeat mode, a favourite or a config value
 * lands on the same state either way. The ones listed here do not, because they ADD,
 * CREATE or STEP relative to the current state.
 *
 * Measured on 2026-08-28 with a briefly slow server: one Smart Mix sent the same
 * 27-track `play_media` twice, 140 ms apart, and each failed attempt cost 60 seconds
 * instead of 30 because the retry doubled the wait. The build took over five minutes
 * and looked frozen.
 *
 * Reads are all retryable and are not listed: a repeated query returns the same data.
 */
private val NON_IDEMPOTENT_COMMANDS: Set<String> = setOf(
    // Appends tracks to a queue. The failure that started this.
    MaCommands.PlayerQueues.PLAY_MEDIA,
    // Both address items by position, so a second run acts on a different item.
    MaCommands.PlayerQueues.DELETE_ITEM,
    MaCommands.PlayerQueues.MOVE_ITEM,
    // Each creates a new object, so a retry leaves two.
    MaCommands.PlayerQueues.SAVE_AS_PLAYLIST,
    MaCommands.Music.PLAYLISTS_CREATE,
    // Appends to, or removes by position from, an existing playlist.
    MaCommands.Music.PLAYLISTS_ADD_TRACKS,
    MaCommands.Music.PLAYLISTS_REMOVE_TRACKS,
    // Step relative to the current track: a retry skips two.
    MaCommands.Players.CMD_NEXT,
    MaCommands.Players.CMD_PREVIOUS,
)

/**
 * Whether [command] may be re-sent when it times out.
 *
 * Auth is excluded because a second login would be answered on a connection whose
 * first attempt may already have succeeded.
 *
 * When adding a command that adds, creates, or steps, add it to
 * [NON_IDEMPOTENT_COMMANDS] as well.
 */
internal fun isRetryableCommand(command: String): Boolean =
    command != MaCommands.Auth.AUTH &&
        command != MaCommands.Auth.LOGIN &&
        command !in NON_IDEMPOTENT_COMMANDS
