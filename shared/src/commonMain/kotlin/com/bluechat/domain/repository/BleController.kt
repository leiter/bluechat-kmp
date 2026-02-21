package com.bluechat.domain.repository

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BleController {
    val scannedDevices: StateFlow<List<BleDevice>>
    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: Flow<ByteArray>

    fun startScan()
    fun stopScan()
    suspend fun connect(deviceId: String)
    suspend fun disconnect()
    suspend fun send(message: String)
    fun release()
}
