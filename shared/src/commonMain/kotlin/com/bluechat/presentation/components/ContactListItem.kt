package com.bluechat.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bluechat.domain.model.Contact

/**
 * A contact row, as opposed to [DeviceListItem] which shows a radio contact.
 *
 * The difference is the point of B1: a contact persists whether or not it is currently
 * advertising, so this row has to be able to say "not in range" rather than simply vanish the
 * way a scan result does.
 */
@Composable
fun ContactListItem(
    contact: Contact,
    isInRange: Boolean,
    onClick: () -> Unit,
    onFavouriteClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Tapping an out-of-range contact would start a connection that cannot succeed.
            .clickable(enabled = isInRange, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isInRange) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = when {
                    contact.isBlocked -> "Blocked"
                    isInRange -> "In range"
                    else -> "Not in range"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (contact.isBlocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (onFavouriteClick != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onFavouriteClick,
                modifier = Modifier.size(40.dp),
            ) {
                Text(
                    text = if (contact.isFavourite) "★" else "☆",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (contact.isFavourite) {
                        Color(0xFFFFD700)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
