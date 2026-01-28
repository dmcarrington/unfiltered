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
    primary = BrightBlue,
    onPrimary = DarkBlueBackground,
    primaryContainer = DarkBlueSurface,
    onPrimaryContainer = BrightBlueLight,
    secondary = BrightPurple,
    onSecondary = DarkBlueBackground,
    secondaryContainer = DarkBlueCard,
    onSecondaryContainer = BrightPurpleLight,
    tertiary = BrightPurpleMuted,
    onTertiary = Color.White,
    background = DarkBlueBackground,
    onBackground = TextPrimary,
    surface = DarkBlueSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkBlueCard,
    onSurfaceVariant = TextSecondary,
    outline = TextMuted,
    outlineVariant = DarkBlueCard,
    error = ErrorRed,
    errorContainer = ErrorContainer,
    onError = Color.White,
    onErrorContainer = Color.White,
    inverseSurface = TextPrimary,
    inverseOnSurface = DarkBlueBackground,
    inversePrimary = BrightBlueMuted
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
