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
    onToggleFavorite: (deviceId: String, deviceName: String?) -> Unit = { _, _ -> },
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
            if (state.favoriteDevices.isNotEmpty()) {
                item {
                    Text(
                        text = "Favorites",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(state.favoriteDevices) { favorite ->
                    DeviceListItem(
                        device = com.bluechat.domain.model.BleDevice(
                            identifier = favorite.identifier,
                            name = favorite.name,
                            rssi = null,
                        ),
                        onClick = { onDeviceClicked(favorite.identifier) },
                        isFavorite = true,
                        onFavoriteClick = { onToggleFavorite(favorite.identifier, favorite.name) },
                    )
                    HorizontalDivider()
                }
                item {
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                Text(
                    text = "Nearby Devices",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.scannedDevices) { device ->
                DeviceListItem(
                    device = device,
                    onClick = { onDeviceClicked(device.identifier) },
                    isFavorite = device.identifier in state.favoriteDeviceIds,
                    onFavoriteClick = { onToggleFavorite(device.identifier, device.name) },
                )
                HorizontalDivider()
            }
        }
    }
}
