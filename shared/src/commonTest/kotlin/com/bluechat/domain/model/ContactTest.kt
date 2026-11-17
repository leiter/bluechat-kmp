package com.bluechat.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The name-resolution rule is the contract B4 (profile exchange) is built on: once names start
 * arriving over the air, a local rename must keep winning. Tested directly so that breaking it
 * fails here rather than showing up as a peer silently renaming itself on someone's phone.
 */
class ContactTest {

    private fun contact(
        announcedName: String? = null,
        nickname: String? = null,
    ) = Contact(
        contactId = "peer-0123456789abcdef",
        announcedName = announcedName,
        nickname = nickname,
        lastTransportId = "AA:BB:CC:DD:EE:FF",
        firstSeenAt = 0L,
        lastSeenAt = 0L,
    )

    @Test
    fun `nickname wins over the name the peer announced`() {
        assertEquals("Mum", contact(announcedName = "Alice", nickname = "Mum").displayName)
    }

    @Test
    fun `announced name is used when there is no nickname`() {
        assertEquals("Alice", contact(announcedName = "Alice").displayName)
    }

    @Test
    fun `falls back to a truncated id when no name is known`() {
        assertEquals("peer-012", contact().displayName)
    }

    @Test
    fun `a blank nickname does not mask the announced name`() {
        // Blank is how the UI will express "clear my override"; it must not win.
        assertEquals("Alice", contact(announcedName = "Alice", nickname = "   ").displayName)
    }

    @Test
    fun `a blank announced name falls through to the id`() {
        // A peer announcing an empty name must not produce a nameless-looking blank row.
        assertEquals("peer-012", contact(announcedName = "").displayName)
    }

    @Test
    fun `isNamed reflects whether either side supplied a name`() {
        assertFalse(contact().isNamed)
        assertFalse(contact(announcedName = " ", nickname = "").isNamed)
        assertTrue(contact(announcedName = "Alice").isNamed)
        assertTrue(contact(nickname = "Mum").isNamed)
    }
}
