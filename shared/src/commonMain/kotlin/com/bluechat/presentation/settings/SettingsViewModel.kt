package com.bluechat.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.repository.AppPreferences
import com.bluechat.domain.repository.ThemePreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    val state = combine(
        appPreferences.displayName,
        appPreferences.themePreference,
    ) { name, theme ->
        SettingsUiState(
            displayName = name,
            themePreference = theme,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            appPreferences.setDisplayName(name)
        }
    }

    fun updateThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            appPreferences.setThemePreference(theme)
        }
    }
}
