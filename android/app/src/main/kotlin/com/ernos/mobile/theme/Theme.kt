package com.ernos.mobile.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── ErnOS brand colours ────────────────────────────────────────────────────────
private val ErnGreen       = Color(0xFF81C784)   // pastel green
private val ErnGreenDark   = Color(0xFFA5D6A7)   // lighter pastel green for dark theme
private val ErnBackground  = Color(0xFF0D1117)
private val ErnSurface     = Color(0xFF161B22)
private val ErnOnSurface   = Color(0xFFE6EDF3)

val DarkColorScheme = darkColorScheme(
    primary             = ErnGreen,
    onPrimary           = Color(0xFF1B3A1E),
    secondary           = ErnGreenDark,
    onSecondary         = ErnBackground,
    background          = ErnBackground,
    onBackground        = ErnOnSurface,
    surface             = ErnSurface,
    onSurface           = ErnOnSurface,
    surfaceVariant      = Color(0xFF21262D),
    onSurfaceVariant    = Color(0xFF8B949E),
)

val LightColorScheme = lightColorScheme(
    primary             = Color(0xFF4CAF50),
    onPrimary           = Color.White,
    secondary           = Color(0xFF388E3C),
    onSecondary         = Color.White,
    background          = Color(0xFFF5FAF5),
    onBackground        = Color(0xFF1C1B1F),
    surface             = Color.White,
    onSurface           = Color(0xFF1C1B1F),
    surfaceVariant      = Color(0xFFE8F5E9),
    onSurfaceVariant    = Color(0xFF44474F),
)

/**
 * Build a custom [darkColorScheme] from an ARGB [primaryArgb] integer so that
 * the user's chosen colour propagates through the entire Material3 colour system.
 *
 * Complementary colours are derived heuristically to keep the theme coherent.
 */
fun buildCustomColorScheme(primaryArgb: Int, dark: Boolean): androidx.compose.material3.ColorScheme {
    val primary = Color(primaryArgb)
    return if (dark) {
        darkColorScheme(
            primary          = primary,
            onPrimary        = Color.White,
            secondary        = primary.copy(alpha = 0.7f),
            onSecondary      = ErnBackground,
            background       = ErnBackground,
            onBackground     = ErnOnSurface,
            surface          = ErnSurface,
            onSurface        = ErnOnSurface,
            surfaceVariant   = Color(0xFF21262D),
            onSurfaceVariant = Color(0xFF8B949E),
        )
    } else {
        lightColorScheme(
            primary          = primary,
            onPrimary        = Color.White,
            secondary        = primary.copy(alpha = 0.8f),
            onSecondary      = Color.White,
            background       = Color(0xFFF5F7FA),
            onBackground     = Color(0xFF1C1B1F),
            surface          = Color.White,
            onSurface        = Color(0xFF1C1B1F),
            surfaceVariant   = Color(0xFFEAECF0),
            onSurfaceVariant = Color(0xFF44474F),
        )
    }
}

/**
 * ErnOSTheme
 *
 * Supports five theme modes:
 *   - "system"  — follows device dark/light setting (default)
 *   - "dark"    — always dark (ErnOS brand dark scheme)
 *   - "light"   — always light (ErnOS brand light scheme)
 *   - "dynamic" — Material3 dynamic color from wallpaper (Android 12+)
 *   - "custom"  — user-supplied [customPrimaryArgb] primary colour
 *
 * [themeChoice] and [customPrimaryArgb] are persisted in [SettingsViewModel] /
 * DataStore so they survive app restarts.
 */
@Composable
fun ErnOSTheme(
    themeChoice:       String  = "system",
    dynamicColor:      Boolean = true,
    customPrimaryArgb: Int     = 0xFF81C784.toInt(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeChoice) {
        "dark"   -> true
        "light"  -> false
        else     -> systemDark
    }

    val colorScheme = when {
        themeChoice == "custom" -> {
            buildCustomColorScheme(customPrimaryArgb, isDark)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else   -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content     = content,
    )
}
