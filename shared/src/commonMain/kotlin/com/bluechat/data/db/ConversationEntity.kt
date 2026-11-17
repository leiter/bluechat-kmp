package com.bluechat.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-conversation state that cannot be derived from anything else.
 *
 * Deliberately *not* here: `lastMessageAt`, the message preview, and the unread count. Those
 * are facts about `chat_messages`, and storing a second copy would mean writing two tables on
 * every message and owning the bug where they disagree. They are computed instead, by
 * [ConversationDao.observeSummaries]. If that query ever becomes the bottleneck, caching it is
 * a measurable optimisation to make later -- not a shape to commit to now.
 *
 * What is left is genuinely conversation-owned: three flags the user sets.
 *
 * A row is created **lazily**, the first time one of those flags is set. A conversation with no
 * row simply has all three false, which is why the summary query LEFT JOINs this table. So the
 * absence of a row is meaningful and normal, not missing data.
 *
 * [conversationId] matches `chat_messages.deviceId`: a contact id, or the literal `broadcast`
 * for Server Mode's group thread.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val conversationId: String,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
)
