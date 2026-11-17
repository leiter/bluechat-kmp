package com.bluechat.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Someone we have met, keyed by the stable id they announced rather than by however we
 * happened to reach them.
 *
 * This is the Phase A `peers` table grown up. It also replaces Phase 9's `favorite_devices`,
 * which keyed on the Bluetooth address: Android randomises that, so a favourite stored against
 * it silently detached from the person it was meant to mark. There is deliberately no second
 * table -- one row per identity, carrying everything we know about them.
 *
 * Two names, not one, and the distinction matters:
 *  - [announcedName] is what the peer *claims* to be called. It arrives over the air and is
 *    therefore not trustworthy and not ours to keep stable.
 *  - [nickname] is what *we* call them locally. It always wins.
 *
 * The resolved name is computed in [com.bluechat.domain.model.Contact], not stored, so the two
 * can never disagree with a stale third copy. B4 (profile exchange) is then pure wiring: it
 * fills [announcedName] and touches nothing else.
 *
 * [lastTransportId] is the most recent Bluetooth address / CoreBluetooth UUID this contact was
 * seen at. It is expected to change, is indexed because every inbound packet looks a contact up
 * by it, and is explicitly *not* an identity.
 */
@Entity(
    tableName = "contacts",
    indices = [Index(value = ["lastTransportId"])],
)
data class ContactEntity(
    @PrimaryKey
    val contactId: String,
    val announcedName: String?,
    val nickname: String?,
    val lastTransportId: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val isFavourite: Boolean,
    val isBlocked: Boolean,
)
