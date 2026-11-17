package com.bluechat.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.bluechat.domain.repository.AppPreferences
import com.bluechat.domain.repository.ThemePreference
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidAppPreferences(context: Context) : AppPreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("bluechat_prefs", Context.MODE_PRIVATE)

    override val localPeerId: String = prefs.getString(KEY_PEER_ID, null)
        ?: Uuid.random().toString().also { prefs.edit().putString(KEY_PEER_ID, it).apply() }

    private val _displayName = MutableStateFlow(prefs.getString("display_name", "Bluechat") ?: "Bluechat")
    override val displayName: Flow<String> = _displayName.asStateFlow()

    private val _themePreference = MutableStateFlow(
        ThemePreference.entries.getOrElse(prefs.getInt("theme", 0)) { ThemePreference.System }
    )
    override val themePreference: Flow<ThemePreference> = _themePreference.asStateFlow()

    override suspend fun setDisplayName(name: String) {
        prefs.edit().putString("display_name", name).apply()
        _displayName.value = name
    }

    override suspend fun setThemePreference(theme: ThemePreference) {
        prefs.edit().putInt("theme", theme.ordinal).apply()
        _themePreference.value = theme
    }

    private companion object {
        const val KEY_PEER_ID = "local_peer_id"
    }
}
