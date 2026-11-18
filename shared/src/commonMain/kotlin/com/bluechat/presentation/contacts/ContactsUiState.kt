package com.bluechat.presentation.contacts

import com.bluechat.domain.model.Contact
import com.bluechat.domain.presence.Presence

data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val query: String = "",
    /** B5: presence per contact id. Absent means [Presence.NotNearby]. */
    val presence: Map<String, Presence> = emptyMap(),
    val isLoaded: Boolean = false,
) {
    /**
     * Search matches the resolved name, the name the peer claimed, and the id.
     *
     * Including the announced name matters: after a local rename the peer's own name is no
     * longer shown anywhere, so searching for the only name you were ever told would otherwise
     * find nothing.
     */
    val visibleContacts: List<Contact>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return contacts
            return contacts.filter { contact ->
                contact.displayName.contains(q, ignoreCase = true) ||
                    contact.announcedName?.contains(q, ignoreCase = true) == true ||
                    contact.contactId.contains(q, ignoreCase = true)
            }
        }

    fun presenceOf(contact: Contact): Presence = presence[contact.contactId] ?: Presence.NotNearby

    val isEmpty: Boolean get() = isLoaded && contacts.isEmpty()
    val hasNoMatches: Boolean get() = isLoaded && contacts.isNotEmpty() && visibleContacts.isEmpty()
}
