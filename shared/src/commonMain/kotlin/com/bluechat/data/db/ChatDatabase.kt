package com.bluechat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ChatMessageEntity::class], version = 1)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
}
