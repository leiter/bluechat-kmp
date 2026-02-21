package com.bluechat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = DarkSlateGrey,
    onPrimary = Color.White,
    primaryContainer = DarkSlateGreyLight,
    onPrimaryContainer = Color.White,
    secondary = Plum,
    onSecondary = Color.White,
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    background = Color.White,
    onBackground = Color(0xFF1C1B1F),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = OldRose,
    onPrimary = Color.Black,
    primaryContainer = DarkSlateGrey,
    onPrimaryContainer = Vanilla,
    secondary = Plum,
    onSecondary = Color.White,
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun BluechatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
