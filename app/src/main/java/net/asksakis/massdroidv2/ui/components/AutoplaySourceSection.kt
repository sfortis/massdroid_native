package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.AutoplayConfig

/**
 * Autoplay's refill strategy for one queue: which source the server draws from once the
 * queue runs out, and the playlist to draw from when that is the chosen source.
 *
 * Shown as the detail of the "Autoplay source" row, which already names it and carries the chosen
 * source, so this starts straight at the list.
 *
 * The sources are laid out as a list rather than behind a select, for two reasons. The
 * server's titles are whole phrases ("Automatic, similar tracks falling back to your
 * library") which do not fit a row of buttons, and a dropdown here opened over the
 * dialog's own Save button. The other queue settings use the same list for the same
 * reasons, through [QueueOptionRow].
 *
 * The playlist is a [SettingsChoiceRow] instead, because a library can hold a hundred of
 * them and the choice dialog scrolls a list of that length.
 *
 * Every label comes from the server, already localized, so nothing is translated here and
 * a source Music Assistant adds later appears without a code change.
 */
@Composable
fun AutoplaySourceSection(
    config: AutoplayConfig,
    onChanged: suspend (mode: String, playlistUri: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val playlistMode = config.playlistDependsOnMode
    // The playlist source chosen while no playlist is set yet. It is held here and not
    // saved, because the server refills from nothing in that state and the queue simply
    // stops; the mode goes to the server together with the playlist picked for it.
    var choosingPlaylist by remember(config.mode, config.playlistUri) { mutableStateOf(false) }
    val shownMode = if (choosingPlaylist && playlistMode != null) playlistMode else config.mode

    Column(modifier = modifier.fillMaxWidth()) {
        config.modeOptions.forEach { option ->
            val isPlaylistSource = option.value == playlistMode
            QueueOptionRow(
                // Without a playlist in the library the source can never work, so it is
                // shown with the reason rather than left to fail silently.
                option = if (isPlaylistSource && config.playlistOptions.isEmpty() && !option.disabled) {
                    option.copy(disabled = true, disabledReason = "There are no playlists in your library")
                } else {
                    option
                },
                selected = option.value == shownMode,
                // "Global" means "follow the server-wide default" and never says what
                // that default is, so name it here. Shown only for that option, and only
                // once the server has told us.
                resolvesTo = config.globalModeTitle
                    ?.takeIf { option.value == AutoplayConfig.MODE_GLOBAL },
                onSelect = {
                    if (isPlaylistSource && config.playlistUri == null) {
                        choosingPlaylist = true
                    } else {
                        choosingPlaylist = false
                        // Carry the existing playlist through a mode change, so switching
                        // away and back does not silently forget it.
                        scope.launch { onChanged(option.value, config.playlistUri) }
                    }
                }
            )
        }

        val playlistShown = config.playlistApplies || choosingPlaylist
        if (playlistShown && playlistMode != null && config.playlistOptions.isNotEmpty()) {
            val selectedPlaylist = config.playlistOptions.firstOrNull { it.value == config.playlistUri }
            SettingsChoiceRow(
                title = "Playlist",
                icon = null,
                options = config.playlistOptions,
                selectedValue = config.playlistUri,
                // Nothing is chosen on a fresh switch to this source, and the server needs
                // one before it can refill from a playlist. A saved playlist that is no
                // longer in the library is treated the same, rather than shown as a raw URI.
                supporting = if (selectedPlaylist == null) "Choose a playlist" else null,
                onSelect = { uri ->
                    choosingPlaylist = false
                    scope.launch { onChanged(playlistMode, uri) }
                }
            )
            if (config.playlistUri == null) {
                Text(
                    // While choosing, nothing is saved yet and the old source still
                    // applies. Saved without a playlist, which the server's own settings
                    // allow, the queue really does stop.
                    if (choosingPlaylist) {
                        "Pick a playlist to use this source."
                    } else {
                        "Until one is chosen, the queue stops when it runs out."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Aligned with the text of the Playlist row above it.
                    modifier = Modifier.padding(horizontal = SETTINGS_ROW_INSET)
                )
            }
        }
    }
}

/**
 * Why Autoplay does not apply to a dynamic queue: the server refills it from [sourceName]
 * whether Autoplay is on or not.
 */
fun autoplayRefillText(sourceName: String?): String =
    if (sourceName.isNullOrBlank()) "This queue refills itself." else "This queue refills itself from $sourceName."
