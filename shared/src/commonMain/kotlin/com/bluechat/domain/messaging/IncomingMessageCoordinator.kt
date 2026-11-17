package com.bluechat.domain.messaging

import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
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
import kotlinx.coroutines.flow.map
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
        //
        // The role is carried alongside the message because one decision depends on it: only
        // the server keeps a group thread. See [handle].
        merge(
            server.incomingMessages.map { it to Role.SERVER },
            bleController.incomingMessages.map { it to Role.CLIENT },
        )
            .onEach { (message, role) -> handle(message, role) }
            .catch { /* keep the process-wide collector alive */ }
            .launchIn(scope)
    }

    private enum class Role { SERVER, CLIENT }

    private suspend fun handle(message: PeerMessage, role: Role) {
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

            is PeerMessage.ReadReceipt -> {
                // Process-wide on purpose: a receipt can arrive long after the sender closed
                // the conversation, and the status must still be recorded.
                chatRepository.updateMessageStatusByUuid(
                    messageUuid = message.messageId,
                    status = DeliveryStatus.Read,
                    readAt = Clock.System.now().toEpochMilliseconds(),
                )
            }

            is PeerMessage.Typing -> {
                // Transient and only meaningful on screen; ChatViewModel handles it. Nothing
                // to persist, and deliberately no notification.
            }

            is PeerMessage.Text -> {
                // No name to offer here, so an already-announced one is preserved.
                peerDirectory.remember(
                    peerId = message.peerId,
                    transportId = message.transportId,
                )
                val sender = peerDirectory.contactFor(message.peerId)

                // Blocking is enforced here, at the single process-wide entry point, rather
                // than in the UI: a blocked contact's message must not be stored, must not be
                // notified, and must not quietly appear in history later. Their lastSeenAt is
                // still updated above, which is what lets B3 show "last tried to reach you".
                if (sender?.isBlocked == true) return

                store(message.peerId, message.body, message.messageId)

                // Mirror into the group thread so Server Mode's Group Chat shows the
                // conversation, not just our own outgoing broadcasts.
                //
                // Server role only (defect #14). The mirror used to run for both roles, which
                // stored every inbound message twice on a client -- harmless while the client
                // had no Group Chat screen to show it, but it was always a duplicate row, and
                // once the chat list became the home screen it surfaced as a phantom "Group
                // Chat" conversation on a device that has no group.
                if (role == Role.SERVER) {
                    store(BROADCAST_DEVICE_ID, message.body, message.messageId)
                }
                messageNotifier.showMessageNotification(
                    sender?.displayName ?: "Unknown",
                    message.body,
                )
            }
        }
    }

    private suspend fun store(conversationId: String, text: String, messageId: String) {
        chatRepository.insertMessage(
            ChatMessage(
                deviceId = conversationId,
                message = text,
                isFromLocalUser = false,
                timestamp = Clock.System.now().toEpochMilliseconds(),
                // Kept so the read receipt we later send can name this exact message.
                messageUuid = messageId,
            )
        )
    }

    companion object {
        /** Conversation id for the server's Group Chat thread. */
        const val BROADCAST_DEVICE_ID = "broadcast"
    }
}
