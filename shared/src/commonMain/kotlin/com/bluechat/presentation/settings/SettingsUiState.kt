package com.bluechat.presentation.settings

import com.bluechat.domain.repository.ThemePreference

data class SettingsUiState(
    val displayName: String = "Bluechat",
    val themePreference: ThemePreference = ThemePreference.System,
)
