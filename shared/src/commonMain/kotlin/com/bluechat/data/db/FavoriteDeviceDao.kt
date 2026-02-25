package com.bluechat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDeviceDao {
    @Query("SELECT * FROM favorite_devices ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteDeviceEntity>>

    @Query("SELECT * FROM favorite_devices WHERE identifier = :identifier")
    suspend fun getFavoriteById(identifier: String): FavoriteDeviceEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_devices WHERE identifier = :identifier)")
    fun isFavorite(identifier: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteDeviceEntity)

    @Query("DELETE FROM favorite_devices WHERE identifier = :identifier")
    suspend fun delete(identifier: String)
}
