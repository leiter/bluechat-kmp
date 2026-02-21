package com.bluechat.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: String,
    val message: String,
    val isFromLocalUser: Boolean,
    val timestamp: Long,
    val deliveryStatus: String = "Sent",
)
