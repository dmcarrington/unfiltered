package com.nostr.unfiltered.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val UnfilteredColorScheme = darkColorScheme(
    primary = WhitePrimary,
    onPrimary = BlackBackground,
    primaryContainer = GrayLight,
    onPrimaryContainer = WhiteMuted,
    secondary = WhiteDim,
    onSecondary = BlackBackground,
    secondaryContainer = GrayMedium,
    onSecondaryContainer = WhiteMuted,
    tertiary = WhiteMuted,
    onTertiary = BlackBackground,
    background = BlackBackground,
    onBackground = TextPrimary,
    surface = BlackSurface,
    onSurface = TextPrimary,
    surfaceVariant = BlackCard,
    onSurfaceVariant = TextSecondary,
    outline = TextMuted,
    outlineVariant = GrayDark,
    error = ErrorRed,
    errorContainer = ErrorContainer,
    onError = Color.White,
    onErrorContainer = Color.White,
    inverseSurface = TextPrimary,
    inverseOnSurface = BlackBackground,
    inversePrimary = WhiteDim
)

@Composable
fun UnfilteredTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = UnfilteredColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
