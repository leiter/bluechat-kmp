package com.bluechat.presentation.device

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.Contact

data class DeviceUiState(
    val scannedDevices: List<BleDevice> = emptyList(),
    val favouriteContacts: List<Contact> = emptyList(),
    /**
     * Scanned devices we recognise, keyed by the address they are currently advertising at.
     *
     * Needed because the scan gives us addresses while contacts are keyed on identity. A hit
     * here means the device in the list is someone we have met, so it can show their name and
     * offer a star; a miss means it is a stranger, and B1 deliberately offers neither.
     */
    val knownByTransportId: Map<String, Contact> = emptyMap(),
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val connectedDeviceId: String? = null,
    val errorMessage: String? = null,
)
