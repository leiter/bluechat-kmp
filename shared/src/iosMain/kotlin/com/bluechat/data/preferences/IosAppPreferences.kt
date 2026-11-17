package com.bluechat.data.preferences

import com.bluechat.domain.repository.AppPreferences
import com.bluechat.domain.repository.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid
import platform.Foundation.NSUserDefaults

class IosAppPreferences : AppPreferences {

    private val defaults = NSUserDefaults.standardUserDefaults

    override val localPeerId: String = defaults.stringForKey(KEY_PEER_ID)
        ?: Uuid.random().toString().also { defaults.setObject(it, forKey = KEY_PEER_ID) }

    private val _displayName = MutableStateFlow(
        defaults.stringForKey("display_name") ?: "Bluechat"
    )
    override val displayName: Flow<String> = _displayName.asStateFlow()

    private val _themePreference = MutableStateFlow(
        ThemePreference.entries.getOrElse(defaults.integerForKey("theme").toInt()) { ThemePreference.System }
    )
    override val themePreference: Flow<ThemePreference> = _themePreference.asStateFlow()

    override suspend fun setDisplayName(name: String) {
        defaults.setObject(name, forKey = "display_name")
        _displayName.value = name
    }

    override suspend fun setThemePreference(theme: ThemePreference) {
        defaults.setInteger(theme.ordinal.toLong(), forKey = "theme")
        _themePreference.value = theme
    }

    private companion object {
        const val KEY_PEER_ID = "local_peer_id"
    }
}
