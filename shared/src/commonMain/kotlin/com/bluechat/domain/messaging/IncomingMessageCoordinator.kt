package com.bluechat.domain.messaging

import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.notification.MessageNotifier
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock

/**
 * Owns the one and only subscription to inbound BLE traffic.
 *
 * Previously each chat ViewModel subscribed in its own `init`, so a message was only stored
 * (and only raised a notification) while that exact conversation was on screen. Because the
 * underlying [kotlinx.coroutines.flow.MutableSharedFlow] has no replay buffer, anything that
 * arrived while the app was backgrounded, on the device list, or in a different conversation
 * was dropped on the floor and lost permanently.
 *
 * This collector is started once at app launch and lives for the whole process, so every
 * inbound message is persisted exactly once. ViewModels now only *read* from the database.
 */
class IncomingMessageCoordinator(
    private val server: BlePeripheralServer,
    private val bleController: BleController,
    private val chatRepository: ChatRepository,
    private val messageNotifier: MessageNotifier,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true

        // Peripheral/server role: messages written by connected clients.
        server.incomingMessages
            .onEach { (clientId, data) ->
                val text = data.decodeToString()
                store(clientId, text)
                // Mirror into the group thread so Server Mode's Group Chat shows the
                // conversation, not just our own outgoing broadcasts.
                store(BROADCAST_DEVICE_ID, text)
                messageNotifier.showMessageNotification("Client", text)
            }
            .catch { /* keep the process-wide collector alive */ }
            .launchIn(scope)

        // Central/client role: notifications from the peripheral we are connected to.
        bleController.incomingMessages
            .onEach { (deviceId, data) ->
                val text = data.decodeToString()
                store(deviceId, text)
                messageNotifier.showMessageNotification("Remote", text)
            }
            .catch { /* keep the process-wide collector alive */ }
            .launchIn(scope)
    }

    private suspend fun store(deviceId: String, text: String) {
        chatRepository.insertMessage(
            ChatMessage(
                deviceId = deviceId,
                message = text,
                isFromLocalUser = false,
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
        )
    }

    companion object {
        /** Conversation id for the server's Group Chat thread. */
        const val BROADCAST_DEVICE_ID = "broadcast"
    }
}
