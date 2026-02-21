package com.bluechat.domain.repository

import kotlinx.coroutines.flow.Flow

interface AppPreferences {
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
