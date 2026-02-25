package com.bluechat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE deviceId = :deviceId ORDER BY timestamp ASC")
    fun getMessagesForDevice(deviceId: String): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: Long, status: String)

    @Query("UPDATE chat_messages SET deliveryStatus = :status, readAt = :readAt WHERE messageUuid = :messageUuid")
    suspend fun updateMessageStatusByUuid(messageUuid: String, status: String, readAt: Long?)

    @Query("SELECT * FROM chat_messages WHERE messageUuid = :messageUuid LIMIT 1")
    suspend fun getMessageByUuid(messageUuid: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE deviceId = :deviceId AND isFromLocalUser = 0 AND readAt IS NULL ORDER BY timestamp ASC")
    suspend fun getUnreadMessagesForDevice(deviceId: String): List<ChatMessageEntity>

    @Query("UPDATE chat_messages SET readAt = :readAt WHERE id = :id")
    suspend fun markMessageAsRead(id: Long, readAt: Long)

    @Query("DELETE FROM chat_messages WHERE deviceId = :deviceId")
    suspend fun deleteMessagesForDevice(deviceId: String)
}
