package com.bluechat.presentation.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.FavoriteDeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceViewModel(
    private val bleController: BleController,
    private val favoriteDeviceRepository: FavoriteDeviceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceUiState())

    val state = combine(
        _state,
        bleController.scannedDevices,
        bleController.connectionState,
        bleController.lastError,
        favoriteDeviceRepository.getAllFavorites(),
    ) { state, devices, connState, error, favorites ->
        state.copy(
            scannedDevices = devices,
            isConnecting = connState == ConnectionState.Connecting,
            isConnected = connState == ConnectionState.Connected,
            favoriteDevices = favorites,
            favoriteDeviceIds = favorites.map { it.identifier }.toSet(),
            // A scan that dies (missing permission, Bluetooth off, location off) otherwise
            // looks exactly like "no devices nearby".
            errorMessage = error ?: state.errorMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeviceUiState())

    fun startScan() {
        _state.update { it.copy(isScanning = true, errorMessage = null) }
        bleController.clearError()
        bleController.startScan()
    }

    fun stopScan() {
        bleController.stopScan()
        _state.update { it.copy(isScanning = false) }
    }

    fun connectToDevice(deviceId: String) {
        _state.update { it.copy(connectedDeviceId = deviceId, errorMessage = null) }
        bleController.clearError()
        viewModelScope.launch {
            try {
                bleController.connect(deviceId)
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = it.errorMessage ?: e.message) }
            }
        }
    }

    fun disconnectFromDevice() {
        viewModelScope.launch {
            bleController.disconnect()
            _state.update { it.copy(connectedDeviceId = null) }
        }
    }

    fun toggleFavorite(deviceId: String, deviceName: String?) {
        viewModelScope.launch {
            favoriteDeviceRepository.toggleFavorite(deviceId, deviceName)
        }
    }

    // NOTE: deliberately no onCleared()/release() here. BleController is an application-scoped
    // singleton; releasing it when this screen's ViewModel is cleared cancelled its coroutine
    // scope for good, leaving scanning and messaging dead for the rest of the process.
}
