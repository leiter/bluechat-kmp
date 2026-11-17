package com.bluechat.data.repository

import com.bluechat.data.db.PeerDao
import com.bluechat.data.db.PeerEntity
import com.bluechat.domain.repository.PeerDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class PeerDirectoryImpl(
    private val dao: PeerDao,
) : PeerDirectory {

    override suspend fun remember(peerId: String, transportId: String, displayName: String?) {
        val existing = dao.findById(peerId)
        dao.upsert(
            PeerEntity(
                peerId = peerId,
                // Never clobber an announced name with a placeholder.
                displayName = displayName
                    ?: existing?.displayName
                    ?: peerId.take(FALLBACK_NAME_LENGTH),
                lastTransportId = transportId,
                lastSeenAt = Clock.System.now().toEpochMilliseconds(),
            )
        )
    }

    override fun observeConversationKey(transportId: String): Flow<String> =
        dao.observeByTransportId(transportId).map { it?.peerId ?: transportId }

    override fun observeDisplayName(transportId: String): Flow<String?> =
        dao.observeByTransportId(transportId).map { it?.displayName }

    override suspend fun nameOf(peerId: String): String? = dao.findById(peerId)?.displayName

    private companion object {
        const val FALLBACK_NAME_LENGTH = 8
    }
}
