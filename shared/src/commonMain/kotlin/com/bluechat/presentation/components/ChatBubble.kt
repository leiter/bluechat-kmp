package com.bluechat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.ui.theme.OldRose
import com.bluechat.ui.theme.Vanilla
import com.bluechat.util.formatRelativeTime

@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(
                RoundedCornerShape(
                    topStart = if (message.isFromLocalUser) 15.dp else 0.dp,
                    topEnd = 15.dp,
                    bottomStart = 15.dp,
                    bottomEnd = if (message.isFromLocalUser) 0.dp else 15.dp,
                )
            )
            .background(if (message.isFromLocalUser) OldRose else Vanilla)
            .padding(16.dp)
    ) {
        Text(
            text = if (message.isFromLocalUser) "You" else "Remote",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message.message,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 250.dp),
        )
        if (message.timestamp > 0 || (message.isFromLocalUser && message.deliveryStatus != DeliveryStatus.Sent)) {
            Row(modifier = Modifier.align(Alignment.End)) {
                if (message.timestamp > 0) {
                    Text(
                        text = formatRelativeTime(message.timestamp),
                        fontSize = 9.sp,
                        color = Color.Gray,
                    )
                }
                if (message.isFromLocalUser) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (message.deliveryStatus) {
                            DeliveryStatus.Queued -> "Queued"
                            DeliveryStatus.Sending -> "..."
                            DeliveryStatus.Sent -> "\u2713"
                            DeliveryStatus.Delivered -> "\u2713\u2713"
                            DeliveryStatus.Read -> "\u2713\u2713"
                            DeliveryStatus.Failed -> "Failed"
                        },
                        fontSize = 9.sp,
                        color = when (message.deliveryStatus) {
                            DeliveryStatus.Queued -> Color(0xFFFFA500) // Orange
                            DeliveryStatus.Failed -> MaterialTheme.colorScheme.error
                            DeliveryStatus.Read -> Color(0xFF2196F3) // Blue for read
                            else -> Color.Gray
                        },
                    )
                }
            }
        }
    }
}
