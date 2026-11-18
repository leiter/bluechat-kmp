package com.bluechat.domain.messaging

import com.bluechat.domain.model.Contact
import com.bluechat.domain.model.Conversation
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.notification.ConversationVisibility
import com.bluechat.domain.notification.MessageNotifier
import com.bluechat.domain.repository.PeerDirectory
import com.bluechat.presentation.chat.FakeBleController
import com.bluechat.presentation.chat.FakeChatRepository
import com.bluechat.presentation.conversations.FakeConversationRepository
import com.bluechat.presentation.conversations.FakePeripheralServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E4: which inbound messages notify, and under which conversation. */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingMessageCoordinatorTest {

    private val server = FakePeripheralServer()
    private val client = FakeBleController()
    private val chatRepository = FakeChatRepository()
    private val conversations = FakeConversationRepository()
    private val peers = FakePeerDirectory()
    private val notifier = RecordingNotifier()
    private val visibility = ConversationVisibility()

    private fun TestScope.startCoordinator() =
        IncomingMessageCoordinator(
            server = server,
            bleController = client,
            chatRepository = chatRepository,
            peerDirectory = peers,
            messageNotifier = notifier,
            conversationRepository = conversations,
            conversationVisibility = visibility,
            scope = backgroundScope,
        ).start()

    private fun text(peerId: String, body: String = "hi") = PeerMessage.Text(
        transportId = "AA:BB:$peerId",
        peerId = peerId,
        messageId = "msg-$body",
        sentAt = 0L,
        body = body,
    )

    @Test
    fun `a message notifies under its conversation with the sender's name`() =
        runTest(UnconfinedTestDispatcher()) {
            peers.contacts["alice"] = contact("alice", name = "Alice")
            startCoordinator()

            client.emitIncomingMessage(text("alice", "hello"))

            assertEquals(listOf(Shown("alice", "Alice", "hello")), notifier.shown)
            assertEquals(listOf("alice"), chatRepository.insertedMessages.map { it.deviceId })
        }

    @Test
    fun `messages in one conversation share its notification`() =
        runTest(UnconfinedTestDispatcher()) {
            startCoordinator()

            client.emitIncomingMessage(text("alice", "one"))
            client.emitIncomingMessage(text("alice", "two"))
            client.emitIncomingMessage(text("bob", "three"))

            assertEquals(listOf("alice", "alice", "bob"), notifier.shown.map { it.conversationId })
        }

    @Test
    fun `a muted conversation stores the message but does not notify`() =
        runTest(UnconfinedTestDispatcher()) {
            conversations.set(conversation("alice", muted = true))
            startCoordinator()

            client.emitIncomingMessage(text("alice"))

            assertTrue(notifier.shown.isEmpty())
            assertEquals(1, chatRepository.insertedMessages.size)
        }

    @Test
    fun `the conversation on screen does not notify, others still do`() =
        runTest(UnconfinedTestDispatcher()) {
            startCoordinator()
            visibility.show("alice")

            client.emitIncomingMessage(text("alice"))
            client.emitIncomingMessage(text("bob"))

            assertEquals(listOf("bob"), notifier.shown.map { it.conversationId })
        }

    @Test
    fun `a blocked sender is neither stored nor notified`() =
        runTest(UnconfinedTestDispatcher()) {
            peers.contacts["mallory"] = contact("mallory", blocked = true)
            startCoordinator()

            client.emitIncomingMessage(text("mallory"))

            assertTrue(notifier.shown.isEmpty())
            assertTrue(chatRepository.insertedMessages.isEmpty())
        }

    @Test
    fun `a server-side message is mirrored to the group thread but notifies once`() =
        runTest(UnconfinedTestDispatcher()) {
            startCoordinator()

            server.emitIncomingMessage(text("alice"))

            assertEquals(
                listOf("alice", IncomingMessageCoordinator.BROADCAST_DEVICE_ID),
                chatRepository.insertedMessages.map { it.deviceId },
            )
            assertEquals(listOf("alice"), notifier.shown.map { it.conversationId })
        }

    @Test
    fun `opening a conversation clears its notification`() =
        runTest(UnconfinedTestDispatcher()) {
            startCoordinator()

            visibility.show("alice")

            assertEquals(listOf("alice"), notifier.cancelled)
        }

    private fun contact(id: String, name: String? = null, blocked: Boolean = false) = Contact(
        contactId = id,
        announcedName = name,
        nickname = null,
        lastTransportId = "AA:BB:$id",
        firstSeenAt = 0L,
        lastSeenAt = 0L,
        isBlocked = blocked,
    )

    private fun conversation(id: String, muted: Boolean) = Conversation(
        conversationId = id,
        title = id,
        lastMessageAt = 0L,
        preview = null,
        previewIsFromLocalUser = false,
        unreadCount = 0,
        isGroup = false,
        isKnownContact = true,
        peerTransportId = null,
        isBlocked = false,
        isPinned = false,
        isMuted = muted,
        isArchived = false,
    )
}

private data class Shown(val conversationId: String, val senderName: String, val text: String)

private class RecordingNotifier : MessageNotifier {
    val shown = mutableListOf<Shown>()
    val cancelled = mutableListOf<String>()

    override fun showMessage(conversationId: String, senderName: String, text: String, timestamp: Long) {
        shown += Shown(conversationId, senderName, text)
    }

    override fun cancel(conversationId: String) {
        cancelled += conversationId
    }
}

private class FakePeerDirectory : PeerDirectory {
    val contacts = mutableMapOf<String, Contact>()

    override suspend fun remember(peerId: String, transportId: String, displayName: String?) {}
    override fun observeConversationKey(transportId: String): Flow<String> = flowOf(transportId)
    override fun observeDisplayName(transportId: String): Flow<String?> = flowOf(null)
    override suspend fun nameOf(peerId: String): String? = contacts[peerId]?.displayName
    override suspend fun contactFor(peerId: String): Contact? = contacts[peerId]
}
