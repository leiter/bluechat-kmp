package com.bluechat.presentation.server

import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.Contact
import com.bluechat.domain.notification.ConversationVisibility
import com.bluechat.domain.repository.PeerDirectory
import com.bluechat.presentation.chat.FakeChatRepository
import com.bluechat.presentation.conversations.FakePeripheralServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C3: the server acknowledges what a client sent, while the conversation is on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val server = FakePeripheralServer()
    private val chat = FakeChatRepository()
    private val visibility = ConversationVisibility()

    private val clientAddress = "AA:01"
    private val peerId = "alice"

    @BeforeTest
    fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun createViewModel() = ServerChatViewModel(
        transportId = clientAddress,
        server = server,
        chatRepository = chat,
        peerDirectory = SinglePeerDirectory(peerId),
        conversationVisibility = visibility,
    )

    private fun inbound(uuid: String) = ChatMessage(
        deviceId = peerId,
        message = "from client",
        isFromLocalUser = false,
        messageUuid = uuid,
    )

    @Test
    fun `opening the conversation acknowledges and marks read what the client sent`() = runTest {
        chat.addMessage(inbound("m1").copy(id = 1))
        chat.addMessage(inbound("m2").copy(id = 2))
        chat.addMessage(
            ChatMessage(id = 3, deviceId = peerId, message = "mine", isFromLocalUser = true, messageUuid = "m3"),
        )
        val viewModel = createViewModel()

        viewModel.onVisibilityChanged(true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(clientAddress to "m1", clientAddress to "m2"), server.sentReadReceipts)
        assertTrue(chat.getUnreadMessages(peerId).isEmpty())
        assertEquals(peerId, visibility.visible.value)
    }

    @Test
    fun `nothing is acknowledged while the screen is not visible`() = runTest {
        chat.addMessage(inbound("m1").copy(id = 1))
        createViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(server.sentReadReceipts.isEmpty())
        assertEquals(1, chat.getUnreadMessages(peerId).size)
    }

    @Test
    fun `a message arriving while visible is acknowledged, one after leaving is not`() = runTest {
        val viewModel = createViewModel()
        viewModel.onVisibilityChanged(true)
        dispatcher.scheduler.advanceUntilIdle()

        chat.addMessage(inbound("live").copy(id = 1))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(clientAddress to "live"), server.sentReadReceipts)

        viewModel.onVisibilityChanged(false)
        dispatcher.scheduler.advanceUntilIdle()
        chat.addMessage(inbound("later").copy(id = 2))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(clientAddress to "live"), server.sentReadReceipts)
        assertNotNull(chat.getUnreadMessages(peerId).singleOrNull { it.messageUuid == "later" })
        assertNull(visibility.visible.value)
    }
}

/** A directory that already knows the one peer on this connection. */
private class SinglePeerDirectory(private val peerId: String) : PeerDirectory {
    override suspend fun remember(peerId: String, transportId: String, displayName: String?) {}
    override fun observeConversationKey(transportId: String): Flow<String> = flowOf(peerId)
    override fun observeDisplayName(transportId: String): Flow<String?> = flowOf("Alice")
    override suspend fun nameOf(peerId: String): String? = "Alice"
    override suspend fun contactFor(peerId: String): Contact? = null
}
