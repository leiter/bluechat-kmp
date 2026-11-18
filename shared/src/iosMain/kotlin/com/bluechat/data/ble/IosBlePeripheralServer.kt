package com.bluechat.data.ble

import com.bluechat.data.protocol.Envelope
import com.bluechat.data.protocol.ProtocolCodec
import com.bluechat.data.protocol.toPeerMessage
import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.model.ServerState
import com.bluechat.domain.repository.AppPreferences
import com.bluechat.domain.repository.BlePeripheralServer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBlePeripheralServer(
    private val appPreferences: AppPreferences,
) : BlePeripheralServer {

    private val _serverState = MutableStateFlow(ServerState.Idle)
    override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ConnectedClient>>(emptyList())
    override val connectedClients: StateFlow<List<ConnectedClient>> = _connectedClients.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<PeerMessage> = _incomingMessages.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val codec = ProtocolCodec(nextMessageId = { nextMessageId++ })
    private var nextMessageId: Int = 0

    // CoreBluetooth components
    private var peripheralManager: CBPeripheralManager? = null
    private var messageCharacteristic: CBMutableCharacteristic? = null
    private val connectedCentrals = mutableMapOf<String, CBCentral>()

    // UUIDs
    private val serviceUuid = BleGattProfile.SERVICE_UUID.toCBUUID()
    private val messageCharUuid = BleGattProfile.CHARACTERISTIC_MESSAGE_UUID.toCBUUID()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val peripheralManagerDelegate = PeripheralManagerDelegate()

    private inner class PeripheralManagerDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {

        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            when (peripheral.state) {
                CBManagerStatePoweredOn -> {
                    initService()
                }
                else -> {
                    _lastError.value = "Bluetooth is not powered on"
                    _serverState.value = ServerState.Error
                }
            }
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didAddService: CBService, error: NSError?) {
            if (error != null) {
                _lastError.value = "Could not publish the chat service: ${error.localizedDescription}"
                _serverState.value = ServerState.Error
                return
            }
            // Start advertising after service is added
            val advertisementData = mapOf<Any?, Any?>(
                platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey to listOf(serviceUuid),
                platform.CoreBluetooth.CBAdvertisementDataLocalNameKey to "BlueChat"
            )
            peripheral.startAdvertising(advertisementData)
        }

        override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
            if (error != null) {
                _lastError.value = "Advertising failed: ${error.localizedDescription}"
                _serverState.value = ServerState.Error
                return
            }
            _lastError.value = null
            _serverState.value = ServerState.Advertising
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
            for (request in didReceiveWriteRequests) {
                val attRequest = request as? CBATTRequest ?: continue
                val data = attRequest.value ?: continue
                val central = attRequest.central
                val centralId = central.identifier.UUIDString

                // Track connected client on first write
                if (!connectedCentrals.containsKey(centralId)) {
                    connectedCentrals[centralId] = central
                    updateConnectedClients()
                }

                // One write is one frame; a message may span several.
                val envelope = codec.decode(
                    centralId,
                    data.toByteArray(),
                    Clock.System.now().toEpochMilliseconds(),
                )
                if (envelope != null) {
                    _incomingMessages.tryEmit(envelope.toPeerMessage(centralId))
                }

                // Send success response
                peripheral.respondToRequest(attRequest, CBATTErrorSuccess.toLong())
            }
        }

        /**
         * A central has enabled notifications. This, not the connection coming up, is the
         * first moment a notification will actually reach it -- so this is where we announce
         * who we are.
         */
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic,
        ) {
            val centralId = central.identifier.UUIDString
            if (!connectedCentrals.containsKey(centralId)) {
                connectedCentrals[centralId] = central
                updateConnectedClients()
            }
            scope.launch { announceProfileTo(central) }
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) {
            // Respond with empty data for read requests
            didReceiveReadRequest.setValue(ByteArray(0).toNSData())
            peripheral.respondToRequest(didReceiveReadRequest, CBATTErrorSuccess.toLong())
        }
    }

    private fun initService() {
        val characteristic = CBMutableCharacteristic(
            type = messageCharUuid,
            properties = CBCharacteristicPropertyRead or CBCharacteristicPropertyWrite or CBCharacteristicPropertyNotify,
            value = null, // Dynamic value
            permissions = platform.CoreBluetooth.CBAttributePermissionsReadable or platform.CoreBluetooth.CBAttributePermissionsWriteable
        )
        messageCharacteristic = characteristic

        val service = CBMutableService(type = serviceUuid, primary = true)
        service.setCharacteristics(listOf(characteristic))

        peripheralManager?.addService(service)
    }

    private fun updateConnectedClients() {
        _connectedClients.update {
            connectedCentrals.map { (id, _) ->
                ConnectedClient(
                    identifier = id,
                    name = null // iOS CBCentral doesn't expose device name
                )
            }
        }
    }

    override fun startAdvertising() {
        // Create peripheral manager - it will call delegate when state updates
        peripheralManager = CBPeripheralManager(delegate = peripheralManagerDelegate, queue = null)
    }

    override fun stopAdvertising() {
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
        connectedCentrals.keys.forEach(codec::forget)
        connectedCentrals.clear()
        updateConnectedClients()
        _serverState.value = ServerState.Idle
    }

    private companion object {
        /** ATT default MTU of 23 minus the 3-byte ATT header. */
        const val DEFAULT_MAX_FRAME_SIZE = 20
    }

    override suspend fun sendToAll(messageId: String, body: String) {
        val envelope = textEnvelope(messageId, body)
        for (central in connectedCentrals.values.toList()) {
            sendEnvelope(central, envelope)
        }
    }

    override suspend fun sendTo(transportId: String, messageId: String, body: String) {
        val central = connectedCentrals[transportId] ?: return
        sendEnvelope(central, textEnvelope(messageId, body))
    }

    override suspend fun sendReadReceipt(transportId: String, messageId: String) {
        val central = connectedCentrals[transportId] ?: return
        sendEnvelope(
            central,
            Envelope.ReadReceipt(senderId = appPreferences.localPeerId, messageId = messageId),
        )
    }

    private suspend fun textEnvelope(messageId: String, body: String) = Envelope.Text(
        senderId = appPreferences.localPeerId,
        messageId = messageId,
        sentAt = Clock.System.now().toEpochMilliseconds(),
        body = body,
    )

    private suspend fun announceProfileTo(central: CBCentral) {
        sendEnvelope(
            central,
            Envelope.Profile(
                senderId = appPreferences.localPeerId,
                displayName = appPreferences.displayName.first(),
            ),
        )
    }

    /**
     * Notifies one central, split into frames that fit the size it told CoreBluetooth it can
     * accept. A payload beyond that is silently truncated, which is what used to cap messages
     * at the default 20 bytes.
     */
    private fun sendEnvelope(central: CBCentral, envelope: Envelope) {
        val manager = peripheralManager ?: return
        val characteristic = messageCharacteristic ?: return

        val maxFrameSize = central.maximumUpdateValueLength.toInt()
            .takeIf { it > 0 } ?: DEFAULT_MAX_FRAME_SIZE

        codec.encode(envelope, maxFrameSize).forEach { frame ->
            manager.updateValue(frame.toNSData(), characteristic, listOf(central))
        }
    }

    override fun release() {
        stopAdvertising()
        scope.cancel()
        peripheralManager = null
        messageCharacteristic = null
    }

    // Helper extensions
    private fun kotlin.uuid.Uuid.toCBUUID(): CBUUID = CBUUID.UUIDWithString(this.toString())

    private fun ByteArray.toNSData(): NSData = memScoped {
        NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = this.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) {
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
            }
        }
        return bytes
    }
}
