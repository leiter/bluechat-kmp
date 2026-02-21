package com.bluechat.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.ServerState
import com.bluechat.domain.repository.BlePeripheralServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBlePeripheralServer(
    private val context: Context,
) : BlePeripheralServer {

    companion object {
        private const val TAG = "BlePeripheralServer"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private val connectedDevices = mutableListOf<BluetoothDevice>()

    private val _serverState = MutableStateFlow(ServerState.Idle)
    override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ConnectedClient>>(emptyList())
    override val connectedClients: StateFlow<List<ConnectedClient>> = _connectedClients.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<Pair<String, ByteArray>> = _incomingMessages.asSharedFlow()

    private fun kotlinUuidToJava(uuid: kotlin.uuid.Uuid): UUID =
        UUID.fromString(uuid.toString())

    private val serviceUuid = kotlinUuidToJava(BleGattProfile.SERVICE_UUID)
    private val messageCharUuid = kotlinUuidToJava(BleGattProfile.CHARACTERISTIC_MESSAGE_UUID)
    private val messageDescUuid = kotlinUuidToJava(BleGattProfile.DESCRIPTOR_MESSAGE_UUID)

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.i(TAG, "onConnectionStateChange device=${device.address} status=$status newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    connectedDevices.add(device)
                    updateConnectedClients()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    connectedDevices.remove(device)
                    updateConnectedClients()
                }
            } else {
                Log.e(TAG, "Connection state change error: $status")
                connectedDevices.remove(device)
                updateConnectedClients()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            Log.i(TAG, "onCharacteristicReadRequest ${characteristic.uuid}")
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0)
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            Log.i(TAG, "onCharacteristicWriteRequest ${characteristic.uuid}")
            if (characteristic.uuid == messageCharUuid) {
                _incomingMessages.tryEmit(device.address to value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started successfully")
            _serverState.value = ServerState.Advertising
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            _serverState.value = ServerState.Error
        }
    }

    private fun updateConnectedClients() {
        _connectedClients.update {
            connectedDevices.map { device ->
                ConnectedClient(
                    identifier = device.address,
                    name = device.name,
                )
            }
        }
    }

    private fun initService() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val messageCharacteristic = BluetoothGattCharacteristic(
            messageCharUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val messageDescriptor = BluetoothGattDescriptor(
            messageDescUuid,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ,
        )
        messageCharacteristic.addDescriptor(messageDescriptor)

        service.addCharacteristic(messageCharacteristic)
        gattServer?.addService(service)
    }

    override fun startAdvertising() {
        if (!bluetoothAdapter.isEnabled) {
            _serverState.value = ServerState.Error
            return
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.e(TAG, "BLE Peripheral mode not supported")
            _serverState.value = ServerState.Error
            return
        }

        initService()

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    override fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        updateConnectedClients()
        _serverState.value = ServerState.Idle
    }

    override suspend fun sendToAll(message: String) {
        val server = gattServer ?: return
        val service = server.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(messageCharUuid) ?: return

        val bytes = message.encodeToByteArray()
        for (device in connectedDevices.toList()) {
            characteristic.setValue(bytes)
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    override suspend fun sendTo(clientId: String, message: String) {
        val server = gattServer ?: return
        val service = server.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(messageCharUuid) ?: return

        val device = connectedDevices.find { it.address == clientId } ?: return
        characteristic.setValue(message.encodeToByteArray())
        server.notifyCharacteristicChanged(device, characteristic, false)
    }

    override fun release() {
        stopAdvertising()
    }
}
