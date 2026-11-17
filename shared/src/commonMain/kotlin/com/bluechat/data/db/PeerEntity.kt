package com.bluechat.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A peer we have identified, keyed by the stable id it announced rather than by however we
 * happened to reach it. [lastTransportId] is the most recent Bluetooth address / CoreBluetooth
 * UUID it was seen at, and is expected to change over time.
 *
 * This is the minimum needed to route messages into the right conversation. Phase B grows it
 * into a proper Contact (nickname override, blocking, favourites).
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    val peerId: String,
    val displayName: String,
    val lastTransportId: String,
    val lastSeenAt: Long,
)
