package com.bluechat.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'Sent'"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorite_devices (
                        identifier TEXT PRIMARY KEY NOT NULL,
                        name TEXT,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        deviceId TEXT NOT NULL,
                        message TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN messageUuid TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN readAt INTEGER DEFAULT NULL"
                )
            }
        }

        /**
         * Adds the peer directory.
         *
         * NOTE ON NUMBERING: this shipped on the Phase A branch as MIGRATION_2_3, which
         * collided with the favourites migration of the same number on `dev`. Two different
         * schemas claimed version 3. The published chain (favourites, pending messages,
         * message uuid) is authoritative because it may already exist in installs, so the
         * peer directory was renumbered to the end rather than wedged into the middle.
         *
         * A device that ran the pre-merge Phase A build has a database stamped version 3 that
         * contains `peers` but not `favorite_devices`. Room will try to run 3->4 on it and
         * fail. Those installs must clear their app data; there is no in-place repair, and the
         * only two that existed are the test phones.
         *
         * Existing messages keep the key they were stored under -- there is no way to recover
         * which stable identity an old Bluetooth address belonged to, so that history stays in
         * its own conversation.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS peers (
                        peerId TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        lastTransportId TEXT NOT NULL,
                        lastSeenAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object ChatDatabaseConstructor : RoomDatabaseConstructor<ChatDatabase> {
    override fun initialize(): ChatDatabase
}
