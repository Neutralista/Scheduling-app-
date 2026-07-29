package com.waypoint.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Dark palette — deep plum surfaces, barley gold accent ────────────────────

private val Dark = darkColorScheme(
    primary                = Color(0xFFD4A84E),  // barley gold
    onPrimary              = Color(0xFF1C0C00),
    primaryContainer       = Color(0xFF40250A),  // dark amber
    onPrimaryContainer     = Color(0xFFF5D98A),  // light gold
    secondary              = Color(0xFFC89AB5),  // dusty mauve
    onSecondary            = Color(0xFF280A20),
    secondaryContainer     = Color(0xFF3D1A34),  // deep plum-purple
    onSecondaryContainer   = Color(0xFFEDD5E8),
    tertiary               = Color(0xFFA8C07A),  // sage
    onTertiary             = Color(0xFF162808),
    tertiaryContainer      = Color(0xFF283814),
    onTertiaryContainer    = Color(0xFFC8E0A8),
    error                  = Color(0xFFFF7777),
    onError                = Color(0xFF2A0000),
    errorContainer         = Color(0xFF4A1010),
    onErrorContainer       = Color(0xFFFFBBAA),
    background             = Color(0xFF0F080E),  // near-black deep plum
    onBackground           = Color(0xFFF0D8EC),  // warm light mauve
    surface                = Color(0xFF1C1019),  // dark plum
    onSurface              = Color(0xFFF0D8EC),
    surfaceVariant         = Color(0xFF2C1A28),  // medium plum
    onSurfaceVariant       = Color(0xFFC8A0BC),  // muted warm mauve
    outline                = Color(0xFF4E2A46),  // plum border
    outlineVariant         = Color(0xFF3A1A34),
    inverseSurface         = Color(0xFFF0D8EC),
    inverseOnSurface       = Color(0xFF1C1019),
    inversePrimary         = Color(0xFF8A4A20),
    scrim                  = Color(0xFF000000),
)

// ── Light palette ─────────────────────────────────────────────────────────────

private val Light = lightColorScheme(
    primary                = Color(0xFF7A2E55),  // deep plum (readable on light)
    onPrimary              = Color(0xFFFFFFFF),
    primaryContainer       = Color(0xFFF5D0E8),  // light mauve
    onPrimaryContainer     = Color(0xFF42103A),
    secondary              = Color(0xFF7A5018),  // dark amber/gold
    onSecondary            = Color(0xFFFFFFFF),
    secondaryContainer     = Color(0xFFF5E0A8),  // light gold
    onSecondaryContainer   = Color(0xFF281800),
    tertiary               = Color(0xFF4A7028),
    onTertiary             = Color(0xFFFFFFFF),
    tertiaryContainer      = Color(0xFFD0E8B0),
    onTertiaryContainer    = Color(0xFF162808),
    error                  = Color(0xFFCC0000),
    onError                = Color(0xFFFFFFFF),
    errorContainer         = Color(0xFFFFDAD6),
    onErrorContainer       = Color(0xFF410002),
    background             = Color(0xFFFFF5FA),  // warm light pink-cream
    onBackground           = Color(0xFF1A080F),
    surface                = Color(0xFFFFFFFF),
    onSurface              = Color(0xFF1A080F),
    surfaceVariant         = Color(0xFFF5E0EE),  // light mauve
    onSurfaceVariant       = Color(0xFF5A3850),
    outline                = Color(0xFFA07090),
    outlineVariant         = Color(0xFFD8B0CC),
    inverseSurface         = Color(0xFF1A080F),
    inverseOnSurface       = Color(0xFFFFF5FA),
    inversePrimary         = Color(0xFFD4A84E),
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
