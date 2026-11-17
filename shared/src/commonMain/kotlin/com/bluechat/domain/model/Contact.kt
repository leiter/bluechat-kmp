package com.bluechat.domain.model

/**
 * Someone we have met, identified by [contactId] -- the stable id they announced, not the
 * address we reached them at.
 *
 * Replaces `FavoriteDevice`, which was keyed on the Bluetooth address and so could not survive
 * address randomisation.
 */
data class Contact(
    val contactId: String,
    val announcedName: String?,
    val nickname: String?,
    val lastTransportId: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val isFavourite: Boolean = false,
    val isBlocked: Boolean = false,
) {
    /**
     * What to show. Derived rather than stored, so there is no third copy to fall out of sync.
     *
     * Our own nickname beats the name the peer claims, which is the rule B4 depends on: once
     * profile exchange starts filling [announcedName] over the air, a local rename must not be
     * overwritten by whatever the other side says it is called.
     *
     * The fallback is a truncated id -- ugly on purpose. A contact with no name at all means we
     * received an identity but never a profile, and that should look like missing data rather
     * than like a device called "Unknown".
     */
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() }
            ?: announcedName?.takeIf { it.isNotBlank() }
            ?: contactId.take(SHORT_ID_LENGTH)

    /** True once we have a name from either side; drives "needs a name" affordances in B3. */
    val isNamed: Boolean
        get() = !nickname.isNullOrBlank() || !announcedName.isNullOrBlank()

    companion object {
        const val SHORT_ID_LENGTH = 8
    }
}
