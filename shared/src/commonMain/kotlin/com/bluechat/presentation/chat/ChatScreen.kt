package com.bluechat.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bluechat.domain.model.ConnectionState
import com.bluechat.presentation.components.ChatBubble
import com.bluechat.presentation.components.DateSeparator
import com.bluechat.presentation.components.ReconnectingBanner
import com.bluechat.presentation.components.SignalStrengthIndicator
import com.bluechat.presentation.components.TypingIndicator
import com.bluechat.util.SignalStrength
import com.bluechat.util.isSameDay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSendMessage: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTextChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var messageText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(10.dp),
                            shape = CircleShape,
                            color = when (state.connectionState) {
                                ConnectionState.Connected -> Color(0xFF4CAF50)
                                ConnectionState.Connecting -> Color(0xFFFFC107)
                                ConnectionState.Disconnecting -> Color(0xFFFFC107)
                                ConnectionState.Reconnecting -> Color(0xFFFFC107)
                                ConnectionState.Disconnected -> Color(0xFFF44336)
                            },
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text("Messages")
                        if (state.connectionState == ConnectionState.Connected && state.signalStrength != SignalStrength.None) {
                            Spacer(Modifier.width(8.dp))
                            SignalStrengthIndicator(signalStrength = state.signalStrength)
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.connectionState == ConnectionState.Reconnecting && state.reconnectAttempt > 0) {
                ReconnectingBanner(
                    attempt = state.reconnectAttempt,
                    maxAttempts = 5,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(state.messages) { index, message ->
                    val showDateSeparator = index == 0 || !isSameDay(
                        state.messages[index - 1].timestamp,
                        message.timestamp
                    )
                    if (showDateSeparator && message.timestamp > 0) {
                        DateSeparator(epochMillis = message.timestamp)
                    }
                    ChatBubble(
                        message = message,
                        modifier = Modifier.align(
                            if (message.isFromLocalUser) Alignment.End else Alignment.Start
                        ),
                    )
                }

                if (state.isRemoteTyping) {
                    item {
                        TypingIndicator(
                            modifier = Modifier.align(Alignment.Start),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = messageText,
                    onValueChange = {
                        messageText = it
                        onTextChanged(it)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                )
                Button(
                    onClick = {
                        onSendMessage(messageText)
                        messageText = ""
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Send")
                }
            }
        }
    }
}
