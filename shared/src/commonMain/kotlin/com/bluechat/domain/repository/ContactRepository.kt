package com.bluechat.domain.repository

import com.bluechat.domain.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * Contact-level operations: the things a *person* does to a contact.
 *
 * Kept separate from [PeerDirectory], which does the transport-to-identity translation the
 * message path needs. Both read the same `contacts` table; they are split by caller, not by
 * storage, so a ViewModel cannot accidentally reach the packet-path writes and vice versa.
 */
interface ContactRepository {

    fun observeAll(): Flow<List<Contact>>

    fun observeFavourites(): Flow<List<Contact>>

    suspend fun setFavourite(contactId: String, favourite: Boolean)

    suspend fun toggleFavourite(contactId: String)

    /** Blank clears the override, so the announced name takes over again. */
    suspend fun setNickname(contactId: String, nickname: String?)

    suspend fun setBlocked(contactId: String, blocked: Boolean)

    suspend fun isBlocked(contactId: String): Boolean

    suspend fun delete(contactId: String)
}
