package com.bluechat.presentation.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bluechat.domain.model.ServerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    state: ServerUiState,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onClientClicked: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Mode") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = CircleShape,
                    color = when (state.serverState) {
                        ServerState.Advertising -> Color(0xFF4CAF50)
                        ServerState.Error -> Color(0xFFF44336)
                        ServerState.Idle -> Color.Gray
                    },
                ) {}
                Text(
                    text = when (state.serverState) {
                        ServerState.Advertising -> "Advertising"
                        ServerState.Error -> "Error"
                        ServerState.Idle -> "Idle"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Start/Stop buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartServer,
                    enabled = !state.isAdvertising,
                ) {
                    Text("Start Server")
                }
                OutlinedButton(
                    onClick = onStopServer,
                    enabled = state.isAdvertising,
                ) {
                    Text("Stop Server")
                }
            }

            state.errorMessage?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Connected Clients (${state.connectedClients.size})",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.connectedClients) { client ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClientClicked(client.identifier) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = client.name ?: "Unknown Device",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = client.identifier,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
