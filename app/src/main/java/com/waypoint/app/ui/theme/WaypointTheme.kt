package com.waypoint.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Dark palette (mirrors web --bg, --primary, etc.) ────────────────────────

private val Dark = darkColorScheme(
    primary                = Color(0xFFCC9EFF),
    onPrimary              = Color(0xFF1A0D2E),
    primaryContainer       = Color(0xFF3D2A5A),
    onPrimaryContainer     = Color(0xFFEAD5FF),
    secondary              = Color(0xFF8ABCDE),
    onSecondary            = Color(0xFF0D2A40),
    secondaryContainer     = Color(0xFF2A3A4E),
    onSecondaryContainer   = Color(0xFFC8DFF5),
    tertiary               = Color(0xFF98C988),
    onTertiary             = Color(0xFF0D2A0D),
    tertiaryContainer      = Color(0xFF2E3A2A),
    onTertiaryContainer    = Color(0xFFC5DAB8),
    error                  = Color(0xFFFF7777),
    onError                = Color(0xFF2A0000),
    errorContainer         = Color(0xFF4A1010),
    onErrorContainer       = Color(0xFFFFBBAA),
    background             = Color(0xFF0C0A11),
    onBackground           = Color(0xFFE8E0F0),
    surface                = Color(0xFF17141F),
    onSurface              = Color(0xFFE8E0F0),
    surfaceVariant         = Color(0xFF242032),
    onSurfaceVariant       = Color(0xFFB5ADC6),
    outline                = Color(0xFF36304A),
    outlineVariant         = Color(0xFF2A2440),
    inverseSurface         = Color(0xFFE8E0F0),
    inverseOnSurface       = Color(0xFF17141F),
    inversePrimary         = Color(0xFF6B3D9E),
    scrim                  = Color(0xFF000000),
)

// ── Light palette ────────────────────────────────────────────────────────────

private val Light = lightColorScheme(
    primary                = Color(0xFF6B3D9E),
    onPrimary              = Color(0xFFFFFFFF),
    primaryContainer       = Color(0xFFE9D8FF),
    onPrimaryContainer     = Color(0xFF2C0F58),
    secondary              = Color(0xFF2A6080),
    onSecondary            = Color(0xFFFFFFFF),
    secondaryContainer     = Color(0xFFD8EEFF),
    onSecondaryContainer   = Color(0xFF0D2E4A),
    tertiary               = Color(0xFF2A6020),
    onTertiary             = Color(0xFFFFFFFF),
    tertiaryContainer      = Color(0xFFD6EDCB),
    onTertiaryContainer    = Color(0xFF1A3A10),
    error                  = Color(0xFFCC0000),
    onError                = Color(0xFFFFFFFF),
    errorContainer         = Color(0xFFFFDAD6),
    onErrorContainer       = Color(0xFF410002),
    background             = Color(0xFFF1EDF9),
    onBackground           = Color(0xFF1A1528),
    surface                = Color(0xFFFFFFFF),
    onSurface              = Color(0xFF1A1528),
    surfaceVariant         = Color(0xFFEBE7F4),
    onSurfaceVariant       = Color(0xFF483F60),
    outline                = Color(0xFFCDC6DC),
    outlineVariant         = Color(0xFFD8D2E8),
    inverseSurface         = Color(0xFF1A1528),
    inverseOnSurface       = Color(0xFFF1EDF9),
    inversePrimary         = Color(0xFFCC9EFF),
    scrim                  = Color(0xFF000000),
)

@Composable
fun WaypointTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content
    )
}
