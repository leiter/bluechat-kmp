package com.bluechat.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_devices")
data class FavoriteDeviceEntity(
    @PrimaryKey
    val identifier: String,
    val name: String?,
    val addedAt: Long,
)
