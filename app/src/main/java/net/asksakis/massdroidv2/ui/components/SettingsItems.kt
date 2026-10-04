package net.asksakis.massdroidv2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.AutoplayConfig
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.QueueConfigOption

/**
 * The shared building blocks for every settings surface in the app: the settings screens,
 * the Player Settings dialog and the sheets that tune a group.
 *
 * Settings are plain rows grouped in sections. Each section has a header, its rows follow
 * with no divider between them, and a single divider separates one section from the next.
 * There are no cards. The app's palette is grayscale from end to end, so stacked
 * `surfaceContainerHigh` cards all render as the same grey and a screen built from them
 * reads as one undifferentiated slab instead of separate settings.
 *
 * Every row is a Material `ListItem` with a transparent container, so it takes the colour
 * of whatever it sits on and lines up with the rows the settings category list already
 * draws. Content that is not a row (a header, the detail under an expandable row, a delay
 * control) aligns with the rows through [SETTINGS_ROW_INSET] and [SETTINGS_TEXT_INSET].
 */

/**
 * The heading over a group of rows, with an optional one-line [caption]. The caption is
 * where a group says something that applies to all of its rows, such as when its changes
 * are written.
 */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SETTINGS_ROW_INSET)
            .padding(top = 8.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading() }
    ) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The line between two sections. Rows inside one section are never divided. */
@Composable
fun SettingsSectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.padding(vertical = 8.dp))
}

/**
 * One setting as a plain row: an optional [icon], the [title], a short [supporting] line
 * and an optional [trailing] control. The whole row is the touch target when [onClick] is
 * set.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    SettingsListItem(
        title = title,
        icon = icon,
        supporting = supporting,
        enabled = enabled,
        trailing = trailing,
        modifier = if (onClick != null) {
            modifier.clickable(enabled = enabled, onClick = onClick)
        } else {
            modifier
        }
    )
}

/**
 * A setting that is simply on or off. The row is the toggle and the switch only shows the
 * state: two click targets for one setting is one too many for a screen reader, and a row
 * that ignores taps beside its switch feels broken.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    icon: ImageVector?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true
) {
    SettingsListItem(
        title = title,
        icon = icon,
        supporting = supporting,
        enabled = enabled,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange
        ),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
    )
}

/**
 * A single choice among [options], shown as a row that names the current value and opens
 * [SettingsChoiceDialog] when tapped.
 *
 * One shape for every choice, whatever the number or length of its values: chips wrapped
 * onto several lines once the server's titles got long, and a dropdown opened over the
 * dialog it sat in.
 *
 * [resolvesTo] is what the "global" option currently stands for. A value that only says
 * "follow the default" is useless without naming the default, so the row and the dialog
 * both name it. [supporting] replaces the generated summary, for a value no single option
 * describes.
 */
@Composable
fun SettingsChoiceRow(
    title: String,
    icon: ImageVector?,
    options: List<QueueConfigOption>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    resolvesTo: String? = null,
    enabled: Boolean = true
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        title = title,
        modifier = modifier,
        icon = icon,
        supporting = supporting ?: choiceSummary(options, selectedValue, resolvesTo),
        onClick = { choosing = true },
        enabled = enabled
    )
    if (choosing) {
        SettingsChoiceDialog(
            title = title,
            options = options,
            selectedValue = selectedValue,
            resolvesTo = resolvesTo,
            onSelect = { value ->
                choosing = false
                if (value != selectedValue) onSelect(value)
            },
            onDismiss = { choosing = false }
        )
    }
}

/** A queue config entry as a [SettingsChoiceRow], naming what its "global" value resolves to. */
@Composable
fun SettingsChoiceRow(
    title: String,
    icon: ImageVector?,
    choice: QueueChoice,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsChoiceRow(
        title = title,
        icon = icon,
        options = choice.options,
        selectedValue = choice.value,
        onSelect = onSelect,
        modifier = modifier,
        resolvesTo = choice.globalTitle
    )
}

/**
 * The list behind a [SettingsChoiceRow]. Picking an enabled option applies it and closes
 * the dialog, so there is no confirm button. A disabled option stays visible with the
 * server's reason, which explains more than an option that is silently missing.
 */
@Composable
fun SettingsChoiceDialog(
    title: String,
    options: List<QueueConfigOption>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    resolvesTo: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Scrolls on its own so a long list (seven audio formats, many rooms) never
            // pushes the Cancel button off the screen.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    QueueOptionRow(
                        option = option,
                        selected = option.value == selectedValue,
                        resolvesTo = resolvesTo?.takeIf { option.value == QueueChoice.VALUE_GLOBAL },
                        onSelect = { onSelect(option.value) }
                    )
                }
            }
        },
        confirmButton = {
            MdTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * A row whose detail opens underneath it when tapped: a list too long for a dialog of its
 * own, or a panel of controls rather than a choice.
 *
 * The detail is indented to the row's text rather than its icon, so it reads as part of
 * this setting and not as the next one, and it has no container for the same reason the
 * rows have none. The expanded state is deliberately not saved: a rotation folds the row,
 * and anything that must survive it (a running calibration) is held by the caller.
 */
@Composable
fun SettingsExpandableRow(
    title: String,
    icon: ImageVector?,
    supporting: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsRow(
            title = title,
            icon = icon,
            supporting = supporting,
            onClick = { expanded = !expanded },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailing?.invoke()
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (icon != null) SETTINGS_TEXT_INSET else SETTINGS_ROW_INSET,
                        end = SETTINGS_ROW_INSET,
                        bottom = 8.dp
                    ),
                content = content
            )
        }
    }
}

/** The one `ListItem` every settings row is drawn with, so they cannot drift apart. */
@Composable
private fun SettingsListItem(
    title: String,
    icon: ImageVector?,
    supporting: String?,
    enabled: Boolean,
    modifier: Modifier,
    trailing: @Composable (() -> Unit)?
) {
    // ListItem has no enabled state of its own, so a disabled row dims its text the way
    // Material dims a disabled control.
    val headlineColor = MaterialTheme.colorScheme.onSurface
        .let { if (enabled) it else it.copy(alpha = DISABLED_ALPHA) }
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
        .let { if (enabled) it else it.copy(alpha = DISABLED_ALPHA) }
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = headlineColor,
            supportingColor = secondaryColor,
            leadingIconColor = secondaryColor
        ),
        headlineContent = { Text(title) },
        supportingContent = if (supporting != null) {
            { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        leadingContent = if (icon != null) {
            { Icon(icon, contentDescription = null, modifier = Modifier.size(SETTINGS_ICON_SIZE)) }
        } else {
            null
        },
        trailingContent = trailing
    )
}

/**
 * The line under a choice row: the selected option's title, and for a value that follows
 * the server-wide default, what that default is, as in "Global (Disabled)". A value no
 * option describes is shown as it is rather than hidden.
 */
internal fun choiceSummary(
    options: List<QueueConfigOption>,
    selectedValue: String?,
    resolvesTo: String?
): String? {
    val title = options.firstOrNull { it.value == selectedValue }?.title ?: selectedValue ?: return null
    val global = resolvesTo?.takeIf { selectedValue == QueueChoice.VALUE_GLOBAL } ?: return title
    return "$title ($global)"
}

/**
 * The crossfade type with labels short enough to sit on one line.
 *
 * The server names the values "Standard crossfade" and "Smart crossfade (beat-matched)",
 * which repeat the word already in the row's own title. The app has its own short names
 * for the same values, so it uses those and leaves anything it does not recognise, such as
 * "Global", as the server wrote it.
 *
 * The reason an unavailable type carries is dropped, since it runs to two lines of prose.
 * What "Global" resolves to is kept, because it is derived from these titles rather than
 * carried on the option.
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
 * labels. Disabled values stay visible with their reason.
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
            // The row is the whole target, and it keeps the 48dp minimum even though the
            // radio itself is drawn smaller. The radio takes no onClick of its own: two
            // click semantics on one row is one too many for a screen reader, and the
            // shrunken control on its own would be below the minimum touch size.
            .heightIn(min = MIN_TOUCH_TARGET)
            .selectable(selected = selected, enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drawn smaller than the default so a list of these fits the dialog; the row above
        // supplies the touch area.
        RadioButton(
            selected = selected,
            onClick = null,
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

/**
 * Horizontal inset of a row's content, equal to `ListItem`'s own padding. Headers, dividers
 * and controls that are not rows use it so their edges meet the rows' icons.
 */
internal val SETTINGS_ROW_INSET = 16.dp

/** Size of a row's leading icon. */
private val SETTINGS_ICON_SIZE = 24.dp

/**
 * Where a row's text starts when it has an icon: the inset, the icon, and `ListItem`'s
 * 16dp gap after leading content. The detail of an expandable row starts here.
 */
internal val SETTINGS_TEXT_INSET = SETTINGS_ROW_INSET + SETTINGS_ICON_SIZE + 16.dp

/** Material's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f

/** Radio drawn smaller than default, so a list of them fits the dialog. */
internal val QUEUE_RADIO_SIZE = 32.dp

/** Material's minimum touch target, kept on the ROW rather than on the shrunken radio. */
private val MIN_TOUCH_TARGET = 48.dp
