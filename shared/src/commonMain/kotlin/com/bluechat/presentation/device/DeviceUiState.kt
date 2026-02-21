package com.bluechat.presentation.device

import com.bluechat.domain.model.BleDevice

data class DeviceUiState(
    val scannedDevices: List<BleDevice> = emptyList(),
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val connectedDeviceId: String? = null,
    val errorMessage: String? = null,
)
