package com.bluechat.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<ChatDatabase> {
    val dbFile = context.getDatabasePath("bluechat.db")
    return Room.databaseBuilder<ChatDatabase>(
        context = context,
        name = dbFile.absolutePath,
    )
}
