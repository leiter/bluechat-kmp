package com.bluechat.data.ble

import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.ServerState
import com.bluechat.domain.repository.BlePeripheralServer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
class IosBlePeripheralServer : BlePeripheralServer {

    private val _serverState = MutableStateFlow(ServerState.Idle)
    override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ConnectedClient>>(emptyList())
    override val connectedClients: StateFlow<List<ConnectedClient>> = _connectedClients.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<Pair<String, ByteArray>> = _incomingMessages.asSharedFlow()

    // CoreBluetooth components
    private var peripheralManager: CBPeripheralManager? = null
    private var messageCharacteristic: CBMutableCharacteristic? = null
    private val connectedCentrals = mutableMapOf<String, CBCentral>()

    // UUIDs
    private val serviceUuid = BleGattProfile.SERVICE_UUID.toCBUUID()
    private val messageCharUuid = BleGattProfile.CHARACTERISTIC_MESSAGE_UUID.toCBUUID()

    private val peripheralManagerDelegate = PeripheralManagerDelegate()

    private inner class PeripheralManagerDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {

        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            when (peripheral.state) {
                CBManagerStatePoweredOn -> {
                    initService()
                }
                else -> {
                    _serverState.value = ServerState.Error
                }
            }
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didAddService: CBService, error: NSError?) {
            if (error != null) {
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
                _serverState.value = ServerState.Error
                return
            }
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

                // Emit the incoming message
                val bytes = data.toByteArray()
                _incomingMessages.tryEmit(centralId to bytes)

                // Send success response
                peripheral.respondToRequest(attRequest, CBATTErrorSuccess.toLong())
            }
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
        connectedCentrals.clear()
        updateConnectedClients()
        _serverState.value = ServerState.Idle
    }

    override suspend fun sendToAll(message: String) {
        val manager = peripheralManager ?: return
        val characteristic = messageCharacteristic ?: return

        val data = message.encodeToByteArray().toNSData()
        // Passing null for centrals sends to all subscribed centrals
        manager.updateValue(data, characteristic, null)
    }

    override suspend fun sendTo(clientId: String, message: String) {
        val manager = peripheralManager ?: return
        val characteristic = messageCharacteristic ?: return
        val central = connectedCentrals[clientId] ?: return

        val data = message.encodeToByteArray().toNSData()
        manager.updateValue(data, characteristic, listOf(central))
    }

    override fun release() {
        stopAdvertising()
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
