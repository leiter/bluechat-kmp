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
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.ServerState
import com.bluechat.domain.repository.AppPreferences
import com.bluechat.domain.repository.BlePeripheralServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBlePeripheralServer(
    private val context: Context,
    appPreferences: AppPreferences,
) : BlePeripheralServer {

    companion object {
        private const val TAG = "BlePeripheralServer"
        private const val DEFAULT_DISPLAY_NAME = "Bluechat"
        private const val MAX_ADVERTISED_NAME_LENGTH = 26
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Written from a background coroutine, read when advertising starts: must be @Volatile
    // or the starting thread can observe a stale name.
    @Volatile
    private var currentDisplayName: String = DEFAULT_DISPLAY_NAME

    /** The adapter name we found before we first overwrote it, so we can put it back. */
    private var originalAdapterName: String? = null

    init {
        appPreferences.displayName
            .onEach { currentDisplayName = it }
            .launchIn(scope)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private val connectedDevices = mutableListOf<BluetoothDevice>()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

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
            _lastError.value = null
            _serverState.value = ServerState.Advertising
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE ->
                    "Advertisement payload too large. Try a shorter display name."
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "Too many apps are advertising right now. Try again shortly."
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising."
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal Bluetooth error."
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "This device does not support BLE advertising."
                else -> "Advertising failed (code $errorCode)."
            }
            Log.e(TAG, "Advertising failed: $errorCode - $reason")
            _lastError.value = reason
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

        // Standard Client Characteristic Configuration Descriptor (CCCD), required
        // by GATT clients (incl. kable) to enable notifications.
        messageCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )

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

        // Renaming the adapter is applied asynchronously by the Bluetooth stack. Starting to
        // advertise immediately afterwards captured the OLD name in the payload, so a changed
        // display name only appeared after an app restart. Wait for the rename to land first.
        scope.launch {
            applyDisplayName(currentDisplayName)
            startAdvertisingNow()
        }
    }

    /**
     * Sets the adapter name and waits (briefly) for the stack to report it back. Note this is
     * the phone's system-wide Bluetooth name; [stopAdvertising] restores the original.
     */
    private suspend fun applyDisplayName(rawName: String) {
        if (rawName.isBlank()) return
        // Leave room for the AD header in the 31-byte scan response.
        val name = rawName.take(MAX_ADVERTISED_NAME_LENGTH)
        if (originalAdapterName == null) {
            originalAdapterName = runCatching { bluetoothAdapter.name }.getOrNull()
        }
        if (runCatching { bluetoothAdapter.name }.getOrNull() == name) return

        bluetoothAdapter.name = name
        repeat(20) {
            if (runCatching { bluetoothAdapter.name }.getOrNull() == name) return
            delay(50)
        }
        Log.w(TAG, "Adapter name did not settle on '$name' before advertising")
    }

    private fun startAdvertisingNow() {
        initService()

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // A 128-bit service UUID already eats 18 of the 31 advertisement bytes, so the
        // device name does not fit alongside it -- anything longer than ~8 characters used
        // to fail outright with ADVERTISE_FAILED_DATA_TOO_LARGE. Carry the name in the scan
        // response instead, which gets its own 31-byte budget; Android merges the two for
        // scanners, so the name still shows up in results.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    override fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        updateConnectedClients()
        _serverState.value = ServerState.Idle

        // Don't leave the user's phone permanently renamed to our display name.
        originalAdapterName?.let { original ->
            runCatching { bluetoothAdapter.name = original }
            originalAdapterName = null
        }
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
        scope.cancel()
    }
}
