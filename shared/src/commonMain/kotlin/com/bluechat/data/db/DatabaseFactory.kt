package com.bluechat.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

fun createDatabase(builder: RoomDatabase.Builder<ChatDatabase>): ChatDatabase {
    return builder
        .addMigrations(
            ChatDatabase.MIGRATION_1_2,
            ChatDatabase.MIGRATION_2_3,
            ChatDatabase.MIGRATION_3_4,
            ChatDatabase.MIGRATION_4_5,
        )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
