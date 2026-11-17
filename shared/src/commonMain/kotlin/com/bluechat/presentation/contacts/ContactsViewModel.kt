package com.bluechat.presentation.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.repository.ContactRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ContactsViewModel(
    private val contactRepository: ContactRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ContactsUiState())

    val state = combine(
        _state,
        contactRepository.observeAll(),
    ) { state, contacts ->
        state.copy(contacts = contacts, isLoaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContactsUiState())

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** Blank clears the override, so the peer's announced name takes over again. */
    fun rename(contactId: String, nickname: String) {
        viewModelScope.launch { contactRepository.setNickname(contactId, nickname) }
    }

    fun setBlocked(contactId: String, blocked: Boolean) {
        viewModelScope.launch { contactRepository.setBlocked(contactId, blocked) }
    }

    fun setFavourite(contactId: String, favourite: Boolean) {
        viewModelScope.launch { contactRepository.setFavourite(contactId, favourite) }
    }

    /**
     * Forgets the contact record. Chat history is deliberately left alone -- deleting messages
     * is C5's job, and silently destroying a conversation because someone tidied their contact
     * list would be a nasty surprise. The conversation stays, titled by its raw id.
     */
    fun delete(contactId: String) {
        viewModelScope.launch { contactRepository.delete(contactId) }
    }
}
