package com.bluechat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMessageDao {
    @Query("SELECT * FROM pending_messages WHERE deviceId = :deviceId ORDER BY createdAt ASC")
    fun getPendingMessagesForDevice(deviceId: String): Flow<List<PendingMessageEntity>>

    @Query("SELECT * FROM pending_messages WHERE deviceId = :deviceId ORDER BY createdAt ASC")
    suspend fun getPendingMessagesForDeviceOnce(deviceId: String): List<PendingMessageEntity>

    @Query("SELECT COUNT(*) FROM pending_messages WHERE deviceId = :deviceId")
    fun getPendingCountForDevice(deviceId: String): Flow<Int>

    @Insert
    suspend fun insert(message: PendingMessageEntity): Long

    @Query("DELETE FROM pending_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_messages SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: Long)

    @Query("DELETE FROM pending_messages WHERE deviceId = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)
}
