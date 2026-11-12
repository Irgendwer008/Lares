/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.lares_app.core.data.AppTheme
import de.lares_app.core.data.StateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Lares theme: warm clay orange (primary) with olive (secondary) and Pompeian teal (tertiary)
 * accents on parchment surfaces; text in the dark brown of the logo.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF9A5326),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC4),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF5B6531),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFEBAA),
    onSecondaryContainer = Color(0xFF191E00),
    tertiary = Color(0xFF22686F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFABEEF6),
    onTertiaryContainer = Color(0xFF001F23),
    background = Color(0xFFFFF8F3),
    onBackground = Color(0xFF28170B),
    surface = Color(0xFFFFF8F3),
    onSurface = Color(0xFF28170B),
    surfaceVariant = Color(0xFFF3DFD2),
    onSurfaceVariant = Color(0xFF52443B),
    outline = Color(0xFF84746A),
    outlineVariant = Color(0xFFD6C3B7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1E8),
    surfaceContainer = Color(0xFFFBEBE1),
    surfaceContainerHigh = Color(0xFFF5E5DB),
    surfaceContainerHighest = Color(0xFFEFDFD5),
    surfaceDim = Color(0xFFE8D7CD),
    surfaceBright = Color(0xFFFFF8F3),
    // Snackbars (dark bar on a light screen); the action uses inversePrimary – undefined, it
    // would fall back to Material's default violet.
    inverseSurface = Color(0xFF3E2D22),
    inverseOnSurface = Color(0xFFFFEDE4),
    inversePrimary = Color(0xFFFFB783),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB783),
    onPrimary = Color(0xFF532200),
    primaryContainer = Color(0xFF773C11),
    onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = Color(0xFFC3CF90),
    onSecondary = Color(0xFF2D3407),
    secondaryContainer = Color(0xFF434C1C),
    onSecondaryContainer = Color(0xFFDFEBAA),
    tertiary = Color(0xFF8FD1DA),
    onTertiary = Color(0xFF00363C),
    tertiaryContainer = Color(0xFF004F56),
    onTertiaryContainer = Color(0xFFABEEF6),
    background = Color(0xFF1A120D),
    onBackground = Color(0xFFF0DFD6),
    surface = Color(0xFF1A120D),
    onSurface = Color(0xFFF0DFD6),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD6C3B7),
    outline = Color(0xFF9F8D83),
    outlineVariant = Color(0xFF52443B),
    surfaceContainerLowest = Color(0xFF140D08),
    surfaceContainerLow = Color(0xFF231A14),
    surfaceContainer = Color(0xFF271E18),
    surfaceContainerHigh = Color(0xFF322822),
    surfaceContainerHighest = Color(0xFF3D332C),
    surfaceDim = Color(0xFF1A120D),
    surfaceBright = Color(0xFF423731),
    inverseSurface = Color(0xFFF0DFD6),
    inverseOnSurface = Color(0xFF3E2D22),
    inversePrimary = Color(0xFF9A5326),
)

@Composable
fun LaresTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Color scheme of [theme] (for the previews on the setup screen). */
@Composable
fun previewColorScheme(theme: AppTheme, dark: Boolean = isSystemInDarkTheme()): ColorScheme {
    val context = LocalContext.current
    return if (theme == AppTheme.WALLPAPER && wallpaperColorsAvailable) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
}

/** Material You needs Android 12+ – below, the choice isn't offered and the Lares theme is used. */
val wallpaperColorsAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Current theme choice, read once synchronously so the first frame already has the right colors. */
fun initialTheme(store: StateStore): AppTheme = runBlocking { store.state.first().settings.theme }

/** [LaresTheme] following the theme setting (changes apply right away). */
@Composable
fun LaresTheme(store: StateStore, initial: AppTheme, content: @Composable () -> Unit) {
    val flow = remember(store) { store.state.map { it.settings.theme } }
    val theme by flow.collectAsState(initial)
    LaresTheme(dynamicColor = theme == AppTheme.WALLPAPER, content = content)
}
