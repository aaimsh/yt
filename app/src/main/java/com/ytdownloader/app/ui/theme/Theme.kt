package com.ytdownloader.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF0000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCC0000),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF4EA8DE),
    onSecondary = Color.White,
    background = Color(0xFF1A1A2E),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF16213E),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1F2B47),
    onSurfaceVariant = Color(0xFFBBBBBB),
    outline = Color(0xFF444466),
    error = Color(0xFFFF6B6B),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFFF0000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF1976D2),
    onSecondary = Color.White,
    background = Color(0xFFF8F8FF),
    onBackground = Color(0xFF1A1A2E),
    surface = Color.White,
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFF2F2F8),
    onSurfaceVariant = Color(0xFF555555),
    outline = Color(0xFFCCCCDD),
)

@Composable
fun YTDownloaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
