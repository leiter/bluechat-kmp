package com.bluechat.presentation.device

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.FavoriteDevice

data class DeviceUiState(
    val scannedDevices: List<BleDevice> = emptyList(),
    val favoriteDevices: List<FavoriteDevice> = emptyList(),
    val favoriteDeviceIds: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val connectedDeviceId: String? = null,
    val errorMessage: String? = null,
)
