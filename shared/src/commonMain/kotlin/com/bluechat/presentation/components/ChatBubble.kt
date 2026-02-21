package com.bluechat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import com.bluechat.ui.theme.OldRose
import com.bluechat.ui.theme.Vanilla
import com.bluechat.util.formatTimestamp

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
        if (message.timestamp > 0) {
            Text(
                text = formatTimestamp(message.timestamp),
                fontSize = 9.sp,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
