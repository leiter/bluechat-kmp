package com.bluechat.data.repository

import com.bluechat.data.db.FavoriteDeviceDao
import com.bluechat.data.db.FavoriteDeviceEntity
import com.bluechat.domain.model.FavoriteDevice
import com.bluechat.domain.repository.FavoriteDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class FavoriteDeviceRepositoryImpl(
    private val dao: FavoriteDeviceDao,
) : FavoriteDeviceRepository {

    override fun getAllFavorites(): Flow<List<FavoriteDevice>> {
        return dao.getAllFavorites().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun isFavorite(identifier: String): Flow<Boolean> {
        return dao.isFavorite(identifier)
    }

    override suspend fun addFavorite(identifier: String, name: String?) {
        val entity = FavoriteDeviceEntity(
            identifier = identifier,
            name = name,
            addedAt = Clock.System.now().toEpochMilliseconds(),
        )
        dao.insert(entity)
    }

    override suspend fun removeFavorite(identifier: String) {
        dao.delete(identifier)
    }

    override suspend fun toggleFavorite(identifier: String, name: String?) {
        val existing = dao.getFavoriteById(identifier)
        if (existing != null) {
            dao.delete(identifier)
        } else {
            addFavorite(identifier, name)
        }
    }
}

private fun FavoriteDeviceEntity.toDomain() = FavoriteDevice(
    identifier = identifier,
    name = name,
    addedAt = addedAt,
)
