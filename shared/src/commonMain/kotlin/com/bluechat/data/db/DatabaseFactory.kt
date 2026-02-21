package com.bluechat.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

fun createDatabase(builder: RoomDatabase.Builder<ChatDatabase>): ChatDatabase {
    return builder
        .addMigrations(ChatDatabase.MIGRATION_1_2)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
