package com.bluechat.domain.messaging

import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.notification.MessageNotifier
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.domain.repository.PeerDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock

/**
 * Owns the one and only subscription to inbound traffic, in both BLE roles.
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
    private val peerDirectory: PeerDirectory,
    private val messageNotifier: MessageNotifier,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true

        // Both roles produce the same domain type, so they can share one handler. A device can
        // be acting as server and client at the same time.
        merge(server.incomingMessages, bleController.incomingMessages)
            .onEach(::handle)
            .catch { /* keep the process-wide collector alive */ }
            .launchIn(scope)
    }

    private suspend fun handle(message: PeerMessage) {
        when (message) {
            is PeerMessage.Profile -> {
                // Identity announcement: from here on this peer's messages land in a
                // conversation keyed on who they are, not on the address they arrived from.
                peerDirectory.remember(
                    peerId = message.peerId,
                    transportId = message.transportId,
                    displayName = message.displayName,
                )
            }

            is PeerMessage.Text -> {
                // No name to offer here, so an already-announced one is preserved.
                peerDirectory.remember(
                    peerId = message.peerId,
                    transportId = message.transportId,
                )
                store(message.peerId, message.body)
                // Mirror into the group thread so Server Mode's Group Chat shows the
                // conversation, not just our own outgoing broadcasts.
                store(BROADCAST_DEVICE_ID, message.body)
                val sender = peerDirectory.nameOf(message.peerId) ?: "Unknown"
                messageNotifier.showMessageNotification(sender, message.body)
            }
        }
    }

    private suspend fun store(conversationId: String, text: String) {
        chatRepository.insertMessage(
            ChatMessage(
                deviceId = conversationId,
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
