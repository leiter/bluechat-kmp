package com.bluechat.presentation.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.repository.BleController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceViewModel(
    private val bleController: BleController,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceUiState())

    val state = combine(
        _state,
        bleController.scannedDevices,
        bleController.connectionState,
    ) { state, devices, connState ->
        state.copy(
            scannedDevices = devices,
            isConnecting = connState == ConnectionState.Connecting,
            isConnected = connState == ConnectionState.Connected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeviceUiState())

    init {
        bleController.connectionState.onEach { connState ->
            if (connState == ConnectionState.Connected) {
                _state.update { it.copy(connectedDeviceId = it.connectedDeviceId) }
            }
        }.launchIn(viewModelScope)
    }

    fun startScan() {
        _state.update { it.copy(isScanning = true) }
        bleController.startScan()
    }

    fun stopScan() {
        bleController.stopScan()
        _state.update { it.copy(isScanning = false) }
    }

    fun connectToDevice(deviceId: String) {
        _state.update { it.copy(connectedDeviceId = deviceId) }
        viewModelScope.launch {
            try {
                bleController.connect(deviceId)
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun disconnectFromDevice() {
        viewModelScope.launch {
            bleController.disconnect()
            _state.update { it.copy(connectedDeviceId = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleController.release()
    }
}
