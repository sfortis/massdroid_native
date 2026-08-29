package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.AutoplayConfig
import net.asksakis.massdroidv2.domain.model.QueueConfigOption

/**
 * Autoplay's refill strategy for one queue: which source the server draws from once the
 * queue runs out, and the playlist to draw from when that is the chosen source.
 *
 * The sources are laid out as a list rather than behind a select, for two reasons. The
 * server's titles are whole phrases ("Automatic, similar tracks falling back to your
 * library") which do not fit a row of buttons, and a dropdown here opened over the
 * dialog's own Save button. The other queue settings use the same list for the same
 * reasons, through [QueueOptionRow].
 *
 * The playlist keeps a select because a library can hold a hundred of them.
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

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Source",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 2.dp)
        )

        config.modeOptions.forEach { option ->
            QueueOptionRow(
                option = option,
                selected = option.value == config.mode,
                // "Global" means "follow the server-wide default" and never says what
                // that default is, so name it here. Shown only for that option, and only
                // once the server has told us.
                resolvesTo = config.globalModeTitle
                    ?.takeIf { option.value == AutoplayConfig.MODE_GLOBAL },
                onSelect = {
                    // Carry the existing playlist through a mode change, so switching
                    // away and back does not silently forget it.
                    scope.launch { onChanged(option.value, config.playlistUri) }
                }
            )
        }

        if (config.playlistApplies && config.playlistOptions.isNotEmpty()) {
            PlaylistSelect(
                options = config.playlistOptions,
                selectedUri = config.playlistUri,
                onSelect = { uri -> scope.launch { onChanged(config.mode, uri) } },
                modifier = Modifier.padding(start = 12.dp, top = 6.dp)
            )
        }
    }
}

/** The playlist to refill from, shaped like the other selects in this dialog. */
@Composable
private fun PlaylistSelect(
    options: List<QueueConfigOption>,
    selectedUri: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.value == selectedUri }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Playlist",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        // Nothing is chosen on a fresh switch to this source, and the
                        // server needs one before it can refill from a playlist.
                        selected?.title ?: "Choose a playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // Bounded so a hundred playlists scroll inside the menu instead of
                // running the height of the screen.
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            expanded = false
                            if (option.value != selectedUri) onSelect(option.value)
                        },
                        trailingIcon = if (option.value == selectedUri) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}
