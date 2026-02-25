package com.bluechat.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: String,
    val message: String,
    val createdAt: Long,
    val retryCount: Int = 0,
)
