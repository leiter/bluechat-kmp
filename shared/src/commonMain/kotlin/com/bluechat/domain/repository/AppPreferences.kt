package com.bluechat.domain.repository

import kotlinx.coroutines.flow.Flow

interface AppPreferences {

    /**
     * Stable identity for this install, generated once and kept for the life of the app data.
     *
     * Conversations are keyed on this rather than on a Bluetooth address, which changes under
     * Android's address randomisation and is a different kind of value entirely on iOS.
     */
    val localPeerId: String

    val displayName: Flow<String>
    val themePreference: Flow<ThemePreference>
    suspend fun setDisplayName(name: String)
    suspend fun setThemePreference(theme: ThemePreference)
}

enum class ThemePreference {
    System,
    Light,
    Dark,
}
