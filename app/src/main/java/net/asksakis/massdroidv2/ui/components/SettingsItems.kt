package net.asksakis.massdroidv2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.AutoplayConfig
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.QueueConfigOption

/**
 * Settings rows for the player dialog, in the same shape the Settings screen uses: a
 * titled card holding list items, an icon on each, and short choices offered as chips.
 *
 * The dialog used to be a column of bare text and switches, which made it the one settings
 * surface in the app that looked like nothing else in it.
 */

/**
 * A titled group of settings.
 *
 * [caption] is where a group says something true of all of it, such as that its settings
 * reach the server the moment they are chosen rather than when Save is pressed.
 */
@Composable
fun SettingsGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            // One step away from the dialog's own surfaceContainerHigh, so the card reads
            // as a group in both themes rather than melting into the background.
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Column(content = content)
        }
    }
}

/**
 * One queue setting offered as chips: the crossfade type, volume normalization, smart
 * shuffle.
 *
 * Pass a null [choice] to show the row without its options, which is what the crossfade
 * type does while crossfade is switched off.
 */
@Composable
fun QueueChoiceItem(
    label: String,
    icon: ImageVector,
    choice: QueueChoice?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    ChoiceChipsItem(
        label = label,
        icon = icon,
        options = choice?.options.orEmpty(),
        selected = choice?.value,
        onSelect = onSelect,
        modifier = modifier,
        // "Global" only ever says "follow the default" without saying what the default is,
        // which is the one thing worth knowing before choosing it.
        note = choice?.globalTitle
            ?.takeIf { choice.followsGlobal }
            ?.let { "Follows the server: $it" },
        trailing = trailing
    )
}

/**
 * A setting whose values fit on chips.
 *
 * A disabled value stays visible and unselectable rather than being filtered away: the
 * server disables what cannot work in the current setup (smart crossfade needs an analysis
 * provider) and says why underneath, which explains more than an absent option.
 */
@Composable
fun ChoiceChipsItem(
    label: String,
    icon: ImageVector,
    options: List<QueueConfigOption>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = trailing,
        supportingContent = if (options.isEmpty() && note == null) {
            null
        } else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (options.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { option ->
                                FilterChip(
                                    selected = option.value == selected,
                                    enabled = !option.disabled,
                                    onClick = { onSelect(option.value) },
                                    label = { Text(option.title) }
                                )
                            }
                        }
                    }
                    note?.let { SupportingNote(it) }
                    options.filter { it.disabled }.forEach { option ->
                        option.disabledReason?.let { SupportingNote("${option.title}: $it") }
                    }
                }
            }
        }
    )
}

@Composable
private fun SupportingNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** A setting that is simply on or off. */
@Composable
fun SettingsSwitchItem(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

/**
 * A setting whose detail is too long for chips, shown as its current value with the detail
 * opening underneath when the row is tapped.
 *
 * Used where the server's own titles are whole phrases (the Autoplay source) and for the
 * timing controls, which are a panel rather than a choice.
 */
@Composable
fun ExpandableSettingItem(
    label: String,
    icon: ImageVector,
    value: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable { expanded = !expanded },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(label) },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = trailing,
            supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        value.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
            }
        )
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                content()
            }
        }
    }
}

/**
 * The chosen Autoplay source, for its collapsed row. A queue following the server-wide
 * default names what that default currently is, rather than only that something else
 * decides it.
 */
internal fun AutoplayConfig.summary(): String {
    val title = selectedModeOption?.title ?: mode
    val global = globalModeTitle?.takeIf { mode == AutoplayConfig.MODE_GLOBAL } ?: return title
    return "$title ($global)"
}

/**
 * One selectable value in a list, for the choices whose titles are phrases rather than
 * labels. Disabled values stay visible with their reason, as on the chips.
 */
@Composable
internal fun QueueOptionRow(
    option: QueueConfigOption,
    selected: Boolean,
    resolvesTo: String?,
    onSelect: () -> Unit
) {
    val enabled = !option.disabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Scaled down with the text: the default 48dp radio target would leave the rows
        // twice as tall as their content and the list would not fit the dialog.
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
            modifier = Modifier.size(QUEUE_RADIO_SIZE)
        )
        Column(modifier = Modifier.padding(start = 10.dp, top = 5.dp, bottom = 5.dp)) {
            Text(
                option.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            // What this option resolves to takes precedence over the server's generic
            // description, which for "Global" only repeats the option's own name.
            val subtitle = option.disabledReason?.takeIf { option.disabled }
                ?: resolvesTo
                ?: option.description
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Radio target scaled to the smaller row text, so a list of them fits the dialog. */
internal val QUEUE_RADIO_SIZE = 32.dp
