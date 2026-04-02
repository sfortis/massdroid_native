package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val PlayerBadgeColor = Color(0xFFEF5350)
private val PlayerBadgeShape = RoundedCornerShape(4.dp)

@Composable
fun PlayerNameWithBadge(
    name: String,
    isLocalPlayer: Boolean,
    isFollowMePlayer: Boolean = false,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = fontWeight
        )
        if (isLocalPlayer) {
            Spacer(modifier = Modifier.width(8.dp))
            PlayerBadgeChip {
                Text(
                    text = "Local Player",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (isFollowMePlayer) {
            Spacer(modifier = Modifier.width(8.dp))
            PlayerBadgeChip {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Follow Me selected player",
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun PlayerBadgeChip(
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                color = PlayerBadgeColor,
                shape = PlayerBadgeShape
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
