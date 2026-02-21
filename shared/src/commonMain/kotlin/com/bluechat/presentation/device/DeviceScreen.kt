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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bluechat.presentation.components.DeviceListItem

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
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Nearby Devices",
                style = MaterialTheme.typography.headlineMedium,
            )
            IconButton(onClick = onSettingsClicked) {
                Text("\u2699", style = MaterialTheme.typography.headlineSmall)
            }
        }

        Spacer(Modifier.height(8.dp))

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
