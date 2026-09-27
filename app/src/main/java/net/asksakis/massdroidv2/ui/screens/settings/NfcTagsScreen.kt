package net.asksakis.massdroidv2.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.data.nfc.NfcTagRecord
import net.asksakis.massdroidv2.ui.components.MdIconButton
import java.text.DateFormat
import java.util.Date

/**
 * The tags written from this phone, so the user can see what each one starts and where.
 *
 * The list is a record, not the thing that makes a tag work. Each tag holds its own
 * instruction, so one written on another phone is absent here and still plays.
 */
@Composable
fun NfcTagsScreen(
    modifier: Modifier = Modifier,
    viewModel: NfcTagsViewModel = hiltViewModel()
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()

    if (tags.isEmpty()) {
        EmptyTagList(modifier)
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(tags, key = { it.tagId }) { tag ->
            ListItem(
                headlineContent = { Text(tag.label) },
                supportingContent = { Text(describe(tag)) },
                leadingContent = { Icon(Icons.Default.Nfc, contentDescription = null) },
                trailingContent = {
                    MdIconButton(onClick = { viewModel.forget(tag.tagId) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Forget this tag")
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyTagList(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = Icons.Default.Nfc,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "No tags written yet. Press and hold an album or a playlist in your " +
                "library and choose Write NFC tag.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun describe(tag: NfcTagRecord): String {
    val written = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(tag.writtenAtMs))
    return tag.playerName?.let { "$it, written $written" } ?: "Wherever you are, written $written"
}
