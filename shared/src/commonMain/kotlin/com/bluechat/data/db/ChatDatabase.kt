package com.bluechat.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(entities = [ChatMessageEntity::class, PeerEntity::class], version = 3)
@ConstructedBy(ChatDatabaseConstructor::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun peerDao(): PeerDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'Sent'"
                )
            }
        }

        /**
         * Adds the peer directory. Existing messages are left keyed on the Bluetooth address
         * they were stored under -- there is no way to recover which stable identity they
         * belonged to, so that history stays in its own conversation.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
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
