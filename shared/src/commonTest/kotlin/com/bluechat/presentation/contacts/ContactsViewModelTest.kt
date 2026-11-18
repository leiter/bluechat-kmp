package com.bluechat.presentation.contacts

import app.cash.turbine.test
import com.bluechat.domain.presence.PresenceTracker
import com.bluechat.presentation.conversations.FakePeripheralServer
import com.bluechat.presentation.device.FakeBleController
import com.bluechat.presentation.device.FakeContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeContactRepository

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeContactRepository()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val bleController = FakeBleController()

    private fun createViewModel() = ContactsViewModel(
        contactRepository = repository,
        presenceTracker = PresenceTracker(bleController, FakePeripheralServer(), repository, tickMillis = 0),
        bleController = bleController,
    )

    private suspend fun stateAfterLoad(viewModel: ContactsViewModel): ContactsUiState {
        var result: ContactsUiState? = null
        viewModel.state.test {
            awaitItem() // stateIn's initial default
            testDispatcher.scheduler.advanceUntilIdle()
            result = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }
        return result!!
    }

    @Test
    fun `search matches the name a peer announced even after a local rename`() = runTest {
        // The announced name is no longer displayed once renamed, so searching for the only
        // name the user was ever told must still find the contact.
        repository.addContact(contactId = "peer-1", announcedName = "Alice", nickname = "Mum")
        val viewModel = createViewModel()
        viewModel.onQueryChanged("Alice")

        val state = stateAfterLoad(viewModel)
        assertEquals(listOf("peer-1"), state.visibleContacts.map { it.contactId })
    }

    @Test
    fun `search matches the local nickname and the id`() = runTest {
        repository.addContact(contactId = "peer-abc123", announcedName = "Alice", nickname = "Mum")
        val viewModel = createViewModel()

        viewModel.onQueryChanged("mum")
        assertEquals(1, stateAfterLoad(viewModel).visibleContacts.size)

        viewModel.onQueryChanged("abc123")
        assertEquals(1, stateAfterLoad(viewModel).visibleContacts.size)

        viewModel.onQueryChanged("nobody")
        val state = stateAfterLoad(viewModel)
        assertTrue(state.visibleContacts.isEmpty())
        assertTrue(state.hasNoMatches)
    }

    @Test
    fun `an empty contact list is distinguished from a search with no matches`() = runTest {
        val viewModel = createViewModel()
        val empty = stateAfterLoad(viewModel)
        assertTrue(empty.isEmpty)
        assertFalse(empty.hasNoMatches)
    }

    @Test
    fun `renaming sets a nickname and a blank one clears it`() = runTest {
        repository.addContact(contactId = "peer-1", announcedName = "Alice")
        val viewModel = createViewModel()

        viewModel.rename("peer-1", "Mum")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Mum", repository.contact("peer-1")?.displayName)

        // Clearing the override must fall back to what the peer calls itself, not to the id.
        viewModel.rename("peer-1", "   ")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(repository.contact("peer-1")?.nickname)
        assertEquals("Alice", repository.contact("peer-1")?.displayName)
    }

    @Test
    fun `blocking and unblocking reach the repository`() = runTest {
        repository.addContact(contactId = "peer-1")
        val viewModel = createViewModel()

        viewModel.setBlocked("peer-1", true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(repository.isBlocked("peer-1"))

        viewModel.setBlocked("peer-1", false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(repository.isBlocked("peer-1"))
    }

    @Test
    fun `deleting removes the contact`() = runTest {
        repository.addContact(contactId = "peer-1")
        val viewModel = createViewModel()

        viewModel.delete("peer-1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(repository.contact("peer-1"))
    }
}
