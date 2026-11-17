package com.bluechat.domain.repository

import com.bluechat.domain.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * Translates between how we *reach* a peer and who that peer *is*.
 *
 * Conversations are keyed on the stable peer id. Until a peer announces itself we only have
 * its transport address, so [observeConversationKey] starts with that address and switches to
 * the real identity the moment it is known -- at which point the chat screen is already open
 * and simply follows along.
 */
interface PeerDirectory {

    /**
     * Records that [peerId] is currently reachable at [transportId].
     *
     * [displayName] is `null` when the caller has no name to offer (a chat message, say, as
     * opposed to an identity announcement). In that case any previously announced name is
     * kept rather than overwritten.
     */
    suspend fun remember(peerId: String, transportId: String, displayName: String? = null)

    /**
     * The conversation key to use for the peer currently reachable at [transportId]: its stable
     * peer id once known, otherwise [transportId] itself.
     */
    fun observeConversationKey(transportId: String): Flow<String>

    /** Display name a peer announced, if we have ever heard from it. */
    fun observeDisplayName(transportId: String): Flow<String?>

    /**
     * Display name for a known peer id, or `null` if we have never heard from it.
     *
     * This is the *resolved* name: a local nickname wins over whatever the peer announced.
     */
    suspend fun nameOf(peerId: String): String?

    /**
     * The full contact record for a known peer id, or `null` if we have never heard from it.
     *
     * The message path needs more than a name -- it has to know whether the sender is blocked
     * before it stores or notifies.
     */
    suspend fun contactFor(peerId: String): Contact?
}
