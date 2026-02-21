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
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE deviceId = :deviceId")
    suspend fun deleteMessagesForDevice(deviceId: String)
}
