package net.asksakis.massdroidv2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.AutoplayConfig
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.QueueConfigOption

/**
 * Settings pieces for the player dialog, built to the pattern the Edit Room screen
 * established and the rest of the app follows: a section label, then one card per setting,
 * each card carrying its own bold title, its control, and a grey line explaining it.
 *
 * One card per setting is the part that matters. A single card holding every setting with
 * dividers between them, which this dialog had, gives one large grey block where the app
 * everywhere else gives separate blocks that let the settings be told apart at a glance.
 */

/** The heading over a group of setting cards, as Edit Room draws its section headers. */
@Composable
fun SettingsSectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One setting, in its own card.
 *
 * [trailing] is where a switch goes, on the title row. [description] is the grey line
 * underneath, which is where a setting says what it currently resolves to or why one of
 * its values cannot be picked.
 */
@Composable
fun SettingCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    description: String? = null,
    content: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
                trailing?.invoke()
            }
            content?.let {
                Spacer(modifier = Modifier.height(10.dp))
                it()
            }
            description?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A queue setting whose values are offered as chips: the crossfade type, volume
 * normalization, smart shuffle.
 *
 * Pass a null [choice] to show the card without its values, which is what the crossfade
 * type does while crossfade is switched off.
 */
@Composable
fun QueueChoiceCard(
    title: String,
    icon: ImageVector,
    choice: QueueChoice?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    val options = choice?.options.orEmpty()
    SettingCard(
        title = title,
        icon = icon,
        modifier = modifier,
        trailing = trailing,
        // "Global" only ever says "follow the default" without saying what the default is,
        // which is the one thing worth knowing before choosing it. An unavailable value
        // explains itself here too.
        description = listOfNotNull(
            choice?.globalTitle
                ?.takeIf { choice.followsGlobal }
                ?.let { "Server default: $it" },
            options.filter { it.disabled }
                .mapNotNull { option -> option.disabledReason?.let { "${option.title}: $it" } }
                .joinToString("\n")
                .ifBlank { null }
        ).joinToString("\n").ifBlank { null },
        content = if (options.isEmpty()) {
            null
        } else {
            {
                // A disabled value stays visible and unselectable rather than being
                // filtered away: the server disables what cannot work in the current setup
                // and says why, which explains more than an absent option.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        FilterChip(
                            selected = option.value == choice?.value,
                            enabled = !option.disabled,
                            onClick = { onSelect(option.value) },
                            label = { Text(option.title) }
                        )
                    }
                }
            }
        }
    )
}

/**
 * A setting whose detail is too long for chips, shown as its current value with the detail
 * opening underneath when the card is tapped.
 *
 * Used where the server's own titles are whole phrases (the Autoplay source) and for the
 * timing controls, which are a panel rather than a choice.
 */
@Composable
fun ExpandableSettingCard(
    title: String,
    icon: ImageVector,
    value: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    SettingCard(
        title = title,
        icon = icon,
        modifier = modifier.clickable { expanded = !expanded },
        trailing = trailing
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) { content() }
            }
        }
    }
}

/** A setting that is simply on or off, with nothing to configure underneath. */
@Composable
fun SettingsSwitchCard(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    SettingCard(
        title = title,
        icon = icon,
        modifier = modifier,
        description = description,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

/**
 * The crossfade type with labels short enough to sit on one line.
 *
 * The server names the values "Standard crossfade" and "Smart crossfade (beat-matched)",
 * which repeat the word already in the card's own title. The app has its own short names
 * for the same values, so it uses those and leaves anything it does not recognise, such as
 * "Global", as the server wrote it.
 *
 * The reason an unavailable type carries is dropped, since it runs to two lines of prose,
 * but what "Global" resolves to is kept: a value that only says "follow the default" is
 * useless without naming the default, and in a card of its own there is room to say it.
 */
internal fun QueueChoice.withShortTitles(): QueueChoice = copy(
    options = options.map { option ->
        option.copy(
            title = CrossfadeMode.entries
                .firstOrNull { it.apiValue == option.value }
                ?.label
                ?: option.title,
            disabledReason = null,
            description = null
        )
    }
)

/**
 * The chosen Autoplay source, for its collapsed card. A queue following the server-wide
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
