package com.bluechat.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import platform.Foundation.NSHomeDirectory

fun getDatabaseBuilder(): RoomDatabase.Builder<ChatDatabase> {
    val dbFilePath = NSHomeDirectory() + "/bluechat.db"
    return Room.databaseBuilder<ChatDatabase>(
        name = dbFilePath,
    )
}
