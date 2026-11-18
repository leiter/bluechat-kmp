package com.bluechat.domain.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationVisibilityTest {

    private val visibility = ConversationVisibility()

    @Test
    fun `hiding a conversation that is no longer visible changes nothing`() {
        visibility.show("alice")
        visibility.show("bob")

        // A late pause from the previous screen must not clear the new one.
        visibility.hide("alice")

        assertEquals("bob", visibility.visible.value)
    }

    @Test
    fun `tracking follows the key from transport address to peer id`() =
        runTest(UnconfinedTestDispatcher()) {
            val key = MutableStateFlow("AA:BB:CC")
            val job = visibility.track(key, backgroundScope)
            assertEquals("AA:BB:CC", visibility.visible.value)

            key.value = "peer-alice"
            assertEquals("peer-alice", visibility.visible.value)

            job.cancel()
            assertNull(visibility.visible.value)
        }

    @Test
    fun `a key flow that completes keeps the conversation visible until cancelled`() =
        runTest(UnconfinedTestDispatcher()) {
            val job = visibility.track(flowOf("peer-alice"), backgroundScope)
            assertEquals("peer-alice", visibility.visible.value)

            job.cancel()
            assertNull(visibility.visible.value)
        }
}
