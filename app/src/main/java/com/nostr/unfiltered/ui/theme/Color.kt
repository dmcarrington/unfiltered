package com.nostr.unfiltered.ui.theme

import androidx.compose.ui.graphics.Color

// Unfiltered Minimalist Black Theme
// Pure black OLED-friendly background with white accents

// Backgrounds (pure black for OLED)
val BlackBackground = Color(0xFF000000)
val BlackSurface = Color(0xFF0A0A0A)
val BlackCard = Color(0xFF141414)

// White accents (primary)
val WhitePrimary = Color(0xFFFFFFFF)
val WhiteMuted = Color(0xFFE0E0E0)
val WhiteDim = Color(0xFF9E9E9E)

// Gray scale for depth
val GrayDark = Color(0xFF1A1A1A)
val GrayMedium = Color(0xFF2A2A2A)
val GrayLight = Color(0xFF3A3A3A)

// Gradient (subtle white to gray)
val GradientStart = WhitePrimary
val GradientEnd = WhiteDim

// Text colors
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFBDBDBD)
val TextMuted = Color(0xFF757575)

// Zap colors (gold/orange for Lightning)
val ZapGold = Color(0xFFFFD700)
val ZapOrange = Color(0xFFFF9800)
val ZapAmber = Color(0xFFFFB300)

// Status colors
val SuccessGreen = Color(0xFF4CAF50)
val ErrorRed = Color(0xFFF44336)
val ErrorContainer = Color(0xFF2D1F1F)

// ZapBoost velocity colors
val VelocityHigh = Color(0xFFFFD700)  // Gold - 10k+ sats/hr
val VelocityMedium = Color(0xFFFF9800) // Orange - 1k+ sats/hr
val VelocityLow = Color(0xFF42A5F5)    // Blue - baseline
