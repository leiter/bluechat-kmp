package com.bluechat.data.repository

import com.bluechat.data.db.ContactDao
import com.bluechat.domain.model.Contact
import com.bluechat.domain.repository.PeerDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Reads and writes the same `contacts` table as
 * [com.bluechat.data.repository.ContactRepositoryImpl], but only the columns the message path
 * owns: identity, address and the announced name. It never touches the nickname, favourite or
 * blocked flags, which belong to the user.
 */
class PeerDirectoryImpl(
    private val dao: ContactDao,
) : PeerDirectory {

    override suspend fun remember(peerId: String, transportId: String, displayName: String?) {
        dao.touch(
            contactId = peerId,
            transportId = transportId,
            // NULL leaves any previously announced name in place -- see ContactDao.touch.
            announcedName = displayName?.takeIf { it.isNotBlank() },
            now = Clock.System.now().toEpochMilliseconds(),
        )
    }

    override fun observeConversationKey(transportId: String): Flow<String> =
        dao.observeByTransportId(transportId).map { it?.contactId ?: transportId }

    override fun observeDisplayName(transportId: String): Flow<String?> =
        dao.observeByTransportId(transportId).map { it?.toDomain()?.displayName }

    override suspend fun nameOf(peerId: String): String? =
        dao.findById(peerId)?.toDomain()?.displayName

    /**
     * Resolves to the local nickname when there is one, so a renamed contact is renamed
     * everywhere -- including in notifications, which previously showed whatever name the peer
     * announced for itself.
     */
    override suspend fun contactFor(peerId: String): Contact? = dao.findById(peerId)?.toDomain()
}
