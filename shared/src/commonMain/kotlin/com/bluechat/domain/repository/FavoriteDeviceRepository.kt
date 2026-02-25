package com.bluechat.domain.repository

import com.bluechat.domain.model.FavoriteDevice
import kotlinx.coroutines.flow.Flow

interface FavoriteDeviceRepository {
    fun getAllFavorites(): Flow<List<FavoriteDevice>>
    fun isFavorite(identifier: String): Flow<Boolean>
    suspend fun addFavorite(identifier: String, name: String?)
    suspend fun removeFavorite(identifier: String)
    suspend fun toggleFavorite(identifier: String, name: String?)
}
