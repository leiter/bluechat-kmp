package com.bluechat.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * SCHEMA POLICY (decided 2026-11-17): there are no migrations.
 *
 * A schema change is a version bump and nothing else. Room drops every table and recreates
 * the database empty -- see [createDatabase]. Tests seed whatever preconditions they need.
 *
 * This is only defensible because the only two installs in existence are the test phones,
 * which get `pm clear` routinely anyway. The migration chain this replaced (five hand-written
 * `Migration` objects, v1 through v6) protected nobody, and was already partly fiction: Room
 * had never exported schemas 3 or 4, so two of its links were unvalidated. It also cost real
 * work during the Phase 9 merge, where two branches independently claimed version 3.
 *
 * Before this app is handed to anyone outside those two phones, the then-current schema must
 * be snapshotted as v1 and a genuine chain started from it. That is a release gate.
 */
@Database(
    entities = [
        ChatMessageEntity::class,
        FavoriteDeviceEntity::class,
        PendingMessageEntity::class,
        PeerEntity::class,
    ],
    version = 6,
)
@ConstructedBy(ChatDatabaseConstructor::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun favoriteDeviceDao(): FavoriteDeviceDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun peerDao(): PeerDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object ChatDatabaseConstructor : RoomDatabaseConstructor<ChatDatabase> {
    override fun initialize(): ChatDatabase
}
