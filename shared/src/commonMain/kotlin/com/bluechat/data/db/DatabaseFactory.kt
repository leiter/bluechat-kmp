package com.bluechat.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * No migrations are registered, by design -- see the schema policy on [ChatDatabase].
 *
 * `dropAllTables = true` makes the wipe total rather than leaving orphaned tables behind,
 * which matters because tables get *removed* in this phase (B1 absorbs `favorite_devices`).
 * The downgrade case is covered too, so checking out an older branch does not wedge the app.
 */
fun createDatabase(builder: RoomDatabase.Builder<ChatDatabase>): ChatDatabase {
    return builder
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
