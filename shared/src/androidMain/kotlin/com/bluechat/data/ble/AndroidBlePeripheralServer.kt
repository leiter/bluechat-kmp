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
import com.bluechat.data.protocol.Envelope
import com.bluechat.data.protocol.ProtocolCodec
import com.bluechat.data.protocol.toPeerMessage
import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.PeerMessage
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
import kotlin.time.Clock
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBlePeripheralServer(
    private val context: Context,
    appPreferencesParam: AppPreferences,
) : BlePeripheralServer {

    companion object {
        private const val TAG = "BlePeripheralServer"
        private const val DEFAULT_DISPLAY_NAME = "Bluechat"
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_HEADER_SIZE = 3
        private const val FRAME_PACING_MILLIS = 8L
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Written from a background coroutine, read when advertising starts: must be @Volatile
    // or the starting thread can observe a stale name.
    @Volatile
    private var currentDisplayName: String = DEFAULT_DISPLAY_NAME

    /**
     * Whether the last advertising attempt had to drop the device name to fit.
     *
     * The scan response has its own 31-byte budget, but the phone's Bluetooth name is the
     * user's and can be any length, so it does not always fit. Rather than refuse to advertise,
     * we retry without it -- a peer that connects learns who we are from the profile exchange
     * anyway, so the advertised name is only a hint for first contact.
     */
    @Volatile
    private var advertiseWithoutDeviceName: Boolean = false

    init {
        appPreferencesParam.displayName
            .onEach { currentDisplayName = it }
            .launchIn(scope)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private val connectedDevices = mutableListOf<BluetoothDevice>()

    private val appPreferences: AppPreferences = appPreferencesParam

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _serverState = MutableStateFlow(ServerState.Idle)
    override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ConnectedClient>>(emptyList())
    override val connectedClients: StateFlow<List<ConnectedClient>> = _connectedClients.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<PeerMessage> = _incomingMessages.asSharedFlow()

    private val codec = ProtocolCodec(nextMessageId = { nextMessageId++ })
    private var nextMessageId: Int = 0

    /**
     * Negotiated ATT MTU per connected client. Android reports it through [onMtuChanged] only
     * when the central asks for a change, so absence means the 23-byte default.
     */
    private val clientMtu = mutableMapOf<String, Int>()

    private fun maxFrameSizeFor(address: String): Int =
        (clientMtu[address] ?: DEFAULT_ATT_MTU) - ATT_HEADER_SIZE

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
                    clientMtu.remove(device.address)
                    codec.forget(device.address)
                    updateConnectedClients()
                }
            } else {
                Log.e(TAG, "Connection state change error: $status")
                connectedDevices.remove(device)
                clientMtu.remove(device.address)
                codec.forget(device.address)
                updateConnectedClients()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.i(TAG, "onMtuChanged device=${device.address} mtu=$mtu")
            clientMtu[device.address] = mtu
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
                // One write is one frame; a message may span several.
                val envelope = codec.decode(
                    device.address,
                    value,
                    Clock.System.now().toEpochMilliseconds(),
                )
                if (envelope != null) {
                    _incomingMessages.tryEmit(envelope.toPeerMessage(device.address))
                }
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

            // Announce ourselves only once the client has enabled notifications. Doing it when
            // the ACL link came up was too early -- there was nothing subscribed yet, so the
            // notification was dropped and the peer never learned our identity.
            val isCccd = descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID
            val enablesNotifications =
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)

            if (isCccd && enablesNotifications) {
                scope.launch { announceProfileTo(device) }
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
            // The only thing of variable size left in our payload is the phone's own
            // Bluetooth name, which the user owns and we will not rename. Retry without it
            // rather than failing to advertise; peers still learn who we are on connect.
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && !advertiseWithoutDeviceName) {
                Log.w(TAG, "Advertisement too large with device name; retrying without it")
                advertiseWithoutDeviceName = true
                scope.launch { startAdvertisingNow() }
                return
            }

            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE ->
                    "Advertisement payload too large."
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
                CLIENT_CHARACTERISTIC_CONFIG_UUID,
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

        // B4: the display name is no longer forced into the advertisement, so there is
        // nothing to wait for before advertising -- see applyDisplayName's removal note below.
        advertiseWithoutDeviceName = false
        startAdvertisingNow()
    }

    // REMOVED (B4, defect #7): applyDisplayName(), which set `bluetoothAdapter.name` -- the
    // phone's *system-wide* Bluetooth name, the one that shows up in car head units and every
    // other device's pairing list -- to this app's display name, and restored it on stop. If
    // the process died while advertising, it never got restored at all.
    //
    // It existed because the advertisement was the only place a peer could learn who we were.
    // That is no longer true: both roles exchange an Envelope.Profile as soon as a connection
    // is up, so identity now travels over GATT and the advertised name is just a hint for the
    // first encounter. The scan response still carries the phone's own name, unmodified.

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
            .setIncludeDeviceName(!advertiseWithoutDeviceName)
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
        // Nothing to restore: the adapter name is no longer touched (B4, defect #7).
    }

    override suspend fun sendToAll(messageId: String, body: String) {
        for (device in connectedDevices.toList()) {
            sendEnvelope(device, textEnvelope(messageId, body))
        }
    }

    override suspend fun sendTo(transportId: String, messageId: String, body: String) {
        val device = connectedDevices.find { it.address == transportId } ?: return
        sendEnvelope(device, textEnvelope(messageId, body))
    }

    override suspend fun sendReadReceipt(transportId: String, messageId: String) {
        val device = connectedDevices.find { it.address == transportId } ?: return
        sendEnvelope(
            device,
            Envelope.ReadReceipt(senderId = appPreferences.localPeerId, messageId = messageId),
        )
    }

    private fun textEnvelope(messageId: String, body: String) = Envelope.Text(
        senderId = appPreferences.localPeerId,
        messageId = messageId,
        sentAt = Clock.System.now().toEpochMilliseconds(),
        body = body,
    )

    private suspend fun announceProfileTo(device: BluetoothDevice) {
        sendEnvelope(
            device,
            Envelope.Profile(
                senderId = appPreferences.localPeerId,
                displayName = currentDisplayName,
            ),
        )
    }

    /**
     * Notifies [device] with an envelope, split into frames that fit that client's negotiated
     * MTU. Each notification must be sent on its own; a payload longer than the MTU allows is
     * silently truncated by the stack, which is what used to cap messages at 20 bytes.
     */
    private suspend fun sendEnvelope(device: BluetoothDevice, envelope: Envelope) {
        val server = gattServer ?: return
        val service = server.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(messageCharUuid) ?: return

        val frames = codec.encode(envelope, maxFrameSizeFor(device.address))
        for (frame in frames) {
            characteristic.setValue(frame)
            server.notifyCharacteristicChanged(device, characteristic, false)
            // Back-to-back notifications can outrun the stack's buffers; a short pause is
            // enough to keep a multi-frame message from being dropped mid-way.
            if (frames.size > 1) delay(FRAME_PACING_MILLIS)
        }
    }

    override fun release() {
        stopAdvertising()
        scope.cancel()
    }
}
