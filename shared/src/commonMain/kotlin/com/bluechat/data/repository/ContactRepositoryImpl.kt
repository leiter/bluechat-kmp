package com.bluechat.data.repository

import com.bluechat.data.db.ContactDao
import com.bluechat.data.db.ContactEntity
import com.bluechat.domain.model.Contact
import com.bluechat.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ContactRepositoryImpl(
    private val dao: ContactDao,
) : ContactRepository {

    override fun observeAll(): Flow<List<Contact>> =
        dao.observeAll().map { rows -> rows.map(ContactEntity::toDomain) }

    override fun observeFavourites(): Flow<List<Contact>> =
        dao.observeFavourites().map { rows -> rows.map(ContactEntity::toDomain) }

    override suspend fun setFavourite(contactId: String, favourite: Boolean) {
        dao.setFavourite(contactId, favourite)
    }

    override suspend fun toggleFavourite(contactId: String) {
        // Read-modify-write is acceptable here and nowhere else in this class: the only writer
        // of isFavourite is the user, and they cannot tap two stars at once.
        val current = dao.findById(contactId) ?: return
        dao.setFavourite(contactId, !current.isFavourite)
    }

    override suspend fun setNickname(contactId: String, nickname: String?) {
        dao.setNickname(contactId, nickname?.trim()?.takeIf { it.isNotEmpty() })
    }

    override suspend fun setBlocked(contactId: String, blocked: Boolean) {
        dao.setBlocked(contactId, blocked)
    }

    override suspend fun isBlocked(contactId: String): Boolean = dao.isBlocked(contactId) == true

    override suspend fun delete(contactId: String) {
        dao.delete(contactId)
    }
}

internal fun ContactEntity.toDomain() = Contact(
    contactId = contactId,
    announcedName = announcedName,
    nickname = nickname,
    lastTransportId = lastTransportId,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    isFavourite = isFavourite,
    isBlocked = isBlocked,
)
