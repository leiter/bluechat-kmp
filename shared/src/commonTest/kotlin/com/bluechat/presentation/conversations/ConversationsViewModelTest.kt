package com.bluechat.presentation.conversations

import app.cash.turbine.test
import com.bluechat.domain.model.Conversation
import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.model.ServerState
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeConversationRepository
    private lateinit var server: FakePeripheralServer

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeConversationRepository()
        server = FakePeripheralServer()
    }

    private fun createViewModel() = ConversationsViewModel(repository, server)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `archived conversations are hidden from the main list but counted`() = runTest {
        repository.set(
            conversation("a", lastMessageAt = 200),
            conversation("b", lastMessageAt = 100, isArchived = true),
        )
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // stateIn default
            val state = awaitItem()
            assertEquals(listOf("a"), state.visibleConversations.map { it.conversationId })
            assertEquals(1, state.archivedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling the archive view swaps which conversations are listed`() = runTest {
        repository.set(
            conversation("a", lastMessageAt = 200),
            conversation("b", lastMessageAt = 100, isArchived = true),
        )
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleShowArchived()

        viewModel.state.test {
            awaitItem() // stateIn's initial default
            // The upstream only runs once there is a collector (WhileSubscribed), so the work
            // has to be advanced from inside test {} -- advancing before subscribing does
            // nothing and leaves the default in place.
            testDispatcher.scheduler.advanceUntilIdle()

            val state = expectMostRecentItem()
            assertTrue(state.showArchived)
            assertEquals(listOf("b"), state.visibleConversations.map { it.conversationId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty list is not reported as empty until the first read arrives`() = runTest {
        val viewModel = createViewModel()

        // Before anything is emitted, "no chats yet" must not be shown -- that is a loading
        // state wearing an empty state's clothes.
        assertFalse(viewModel.state.value.isLoaded)
        assertFalse(viewModel.state.value.isEmpty)

        viewModel.state.test {
            awaitItem() // stateIn's initial default, still unloaded
            testDispatcher.scheduler.advanceUntilIdle()

            val state = expectMostRecentItem()
            assertTrue(state.isLoaded)
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pin mute and archive reach the repository`() = runTest {
        repository.set(conversation("a"))
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.togglePinned("a", true)
        viewModel.toggleMuted("a", true)
        viewModel.setArchived("a", true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, repository.find("a")?.isPinned)
        assertEquals(true, repository.find("a")?.isMuted)
        assertEquals(true, repository.find("a")?.isArchived)
    }

    @Test
    fun `unread totals are surfaced for an app level badge`() = runTest {
        repository.set(
            conversation("a", unreadCount = 2),
            conversation("b", unreadCount = 3),
        )
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem()
            assertEquals(5, awaitItem().totalUnread)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a conversation with a peer connected to our server is server side`() = runTest {
        // The peer dialled us, so a reply has to go out through the peripheral role. Routing
        // it to the client screen renders the history and then fails every send.
        repository.set(conversation("peer-1", peerTransportId = "AA:BB:CC:DD:EE:FF"))
        server.setClients("AA:BB:CC:DD:EE:FF")
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem()
            testDispatcher.scheduler.advanceUntilIdle()
            val state = expectMostRecentItem()
            assertTrue(state.isServerSide(state.visibleConversations.first()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a conversation whose peer is not connected to our server is client side`() = runTest {
        repository.set(conversation("peer-1", peerTransportId = "AA:BB:CC:DD:EE:FF"))
        server.setClients()
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem()
            testDispatcher.scheduler.advanceUntilIdle()
            val state = expectMostRecentItem()
            assertFalse(state.isServerSide(state.visibleConversations.first()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a conversation with no known transport is never server side`() = runTest {
        // The group thread and never-identified peers have no address to match.
        repository.set(conversation("broadcast", peerTransportId = null))
        server.setClients("AA:BB:CC:DD:EE:FF")
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem()
            testDispatcher.scheduler.advanceUntilIdle()
            val state = expectMostRecentItem()
            assertFalse(state.isServerSide(state.visibleConversations.first()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a conversation removes it from the list`() = runTest {
        repository.set(conversation("a"), conversation("b"))
        val viewModel = createViewModel()

        viewModel.delete("a")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem()
            testDispatcher.scheduler.advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(listOf("b"), state.visibleConversations.map { it.conversationId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting an archived conversation also removes it from the archive`() = runTest {
        // The archive is the same list filtered, so a delete has to clear it from both views
        // rather than only the one the user was looking at.
        repository.set(conversation("a", isArchived = true))
        val viewModel = createViewModel()
        viewModel.toggleShowArchived()

        viewModel.delete("a")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem()
            testDispatcher.scheduler.advanceUntilIdle()
            val state = expectMostRecentItem()
            assertTrue(state.visibleConversations.isEmpty())
            assertEquals(0, state.archivedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun conversation(
        id: String,
        lastMessageAt: Long = 0L,
        unreadCount: Int = 0,
        isArchived: Boolean = false,
        peerTransportId: String? = null,
    ) = Conversation(
        conversationId = id,
        title = id,
        lastMessageAt = lastMessageAt,
        preview = "hello",
        previewIsFromLocalUser = false,
        unreadCount = unreadCount,
        isGroup = false,
        isKnownContact = true,
        peerTransportId = peerTransportId,
        isBlocked = false,
        isPinned = false,
        isMuted = false,
        isArchived = isArchived,
    )
}

class FakeConversationRepository : ConversationRepository {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())

    fun set(vararg conversations: Conversation) {
        _conversations.value = conversations.toList()
    }

    fun find(id: String) = _conversations.value.find { it.conversationId == id }

    override fun observeAll(): Flow<List<Conversation>> = _conversations

    override fun observeTotalUnread(): Flow<Int> =
        _conversations.map { list -> list.sumOf { it.unreadCount } }

    override suspend fun markRead(conversationId: String) {
        update(conversationId) { it.copy(unreadCount = 0) }
    }

    override suspend fun setPinned(conversationId: String, pinned: Boolean) {
        update(conversationId) { it.copy(isPinned = pinned) }
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean) {
        update(conversationId) { it.copy(isMuted = muted) }
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean) {
        update(conversationId) { it.copy(isArchived = archived) }
    }

    override suspend fun delete(conversationId: String) {
        _conversations.value = _conversations.value.filterNot { it.conversationId == conversationId }
    }

    private fun update(id: String, transform: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map {
            if (it.conversationId == id) transform(it) else it
        }
    }
}

class FakePeripheralServer : BlePeripheralServer {
    private val _connectedClients = MutableStateFlow<List<ConnectedClient>>(emptyList())
    override val connectedClients: StateFlow<List<ConnectedClient>> = _connectedClients
    override val serverState: StateFlow<ServerState> = MutableStateFlow(ServerState.Advertising)
    override val incomingMessages: Flow<PeerMessage> = MutableSharedFlow()

    fun setClients(vararg identifiers: String) {
        _connectedClients.value = identifiers.map { ConnectedClient(it, null) }
    }

    override val lastError: StateFlow<String?> = MutableStateFlow(null)

    override fun startAdvertising() {}
    override fun stopAdvertising() {}
    override suspend fun sendTo(deviceId: String, messageId: String, body: String) {}
    override suspend fun sendToAll(messageId: String, body: String) {}
    override fun release() {}
}
