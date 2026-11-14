package com.bluechat.presentation.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bluechat.presentation.components.DeviceListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    state: DeviceUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceClicked: (String) -> Unit,
    onStartServer: () -> Unit = {},
    onSettingsClicked: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // A Scaffold (rather than a bare Column) is what keeps this screen below the status bar
    // and gives it the themed background. Without it the settings button sat underneath the
    // system status bar and could not be tapped, and the screen rendered light in dark mode.
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nearby Devices") },
                actions = {
                    IconButton(onClick = onSettingsClicked) {
                        Text("⚙", style = MaterialTheme.typography.headlineSmall)
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
                .padding(horizontal = 16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onStartScan, enabled = !state.isScanning) {
                    Text("Start Scan")
                }
                Button(onClick = onStopScan, enabled = state.isScanning) {
                    Text("Stop Scan")
                }
                OutlinedButton(onClick = onStartServer) {
                    Text("Server Mode")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (state.isConnecting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Connecting...",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.scannedDevices) { device ->
                    DeviceListItem(
                        device = device,
                        onClick = { onDeviceClicked(device.identifier) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
