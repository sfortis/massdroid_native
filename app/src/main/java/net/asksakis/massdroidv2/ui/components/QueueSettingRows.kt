package net.asksakis.massdroidv2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.AutoplayConfig
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.QueueConfigOption

/**
 * One queue setting that is a choice between server-declared values: the crossfade type,
 * volume normalization, smart shuffle.
 *
 * Collapsed to a single row showing what is chosen, because a settings dialog that lays
 * every option of every setting out at once is a wall to read and long to scroll. The
 * options open in place under the row rather than in a menu: the titles are the server's
 * own phrases rather than labels this app invents, an option can come back disabled with
 * a reason worth reading, and a dropdown in this dialog opens over its Save button.
 */
@Composable
fun QueueChoiceRow(
    label: String,
    choice: QueueChoice,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle? = null
) {
    ExpandableSettingRow(
        label = label,
        value = choice.summary(),
        modifier = modifier,
        labelStyle = labelStyle
    ) {
        choice.options.forEach { option ->
            QueueOptionRow(
                option = option,
                selected = option.value == choice.value,
                // "Global" only ever says "follow the default" without saying what the
                // default is, which is the one thing worth knowing before choosing it.
                resolvesTo = choice.globalTitle?.takeIf { option.value == QueueChoice.VALUE_GLOBAL },
                onSelect = { onSelect(option.value) }
            )
        }
    }
}

/**
 * What the collapsed row shows on the right.
 *
 * A setting left on "global" names the value it follows in brackets, so the row says what
 * is actually in force rather than only that something else decides it.
 */
private fun QueueChoice.summary(): String {
    val title = selectedOption?.title ?: value
    val global = globalTitle?.takeIf { followsGlobal } ?: return title
    return "$title ($global)"
}

/**
 * The chosen Autoplay source, for its collapsed row. Same rule as [QueueChoice.summary]:
 * a queue following the server-wide default names what that default currently is.
 */
internal fun AutoplayConfig.summary(): String {
    val title = selectedModeOption?.title ?: mode
    val global = globalModeTitle?.takeIf { mode == AutoplayConfig.MODE_GLOBAL } ?: return title
    return "$title ($global)"
}

/**
 * A setting shown as one row, with its detail opening underneath when tapped.
 *
 * The whole row is the target, including the value, so it does not matter where the
 * finger lands.
 */
@Composable
internal fun ExpandableSettingRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle? = null,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = labelStyle ?: MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            Icon(
                if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) { content() }
        }
    }
}

/**
 * One selectable value of a queue setting.
 *
 * A disabled option stays visible and unselectable rather than being filtered away: the
 * server disables what cannot work in the current setup (smart crossfade needs an
 * analysis provider, the "similar" Autoplay source needs a provider that supplies similar
 * tracks) and says why, which explains more to the listener than an absent option.
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
