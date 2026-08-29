package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.QueueConfigOption

/**
 * One queue setting that is a choice between server-declared values: the crossfade type,
 * volume normalization, smart shuffle.
 *
 * All of these arrived as queue configuration in MA 2.10 and share a shape, so they share
 * a control. It is the same list of radio rows the Autoplay source uses, for the same
 * reasons: the titles are the server's own phrases rather than labels this app invents,
 * an option can come back disabled with a reason worth reading, and a dropdown in this
 * dialog opens over its Save button.
 */
@Composable
fun QueueChoiceSection(
    label: String,
    choice: QueueChoice,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 2.dp)
        )
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
