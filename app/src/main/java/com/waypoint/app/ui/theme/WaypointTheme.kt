package com.waypoint.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.waypoint.app.ui.components.toOpaqueColor

// ── Standard — dark palette (deep plum / barley gold) ─────────────────────────

private val StandardDark = darkColorScheme(
    primary                = Color(0xFFD4A84E),
    onPrimary              = Color(0xFF1C0C00),
    primaryContainer       = Color(0xFF40250A),
    onPrimaryContainer     = Color(0xFFF5D98A),
    secondary              = Color(0xFFC89AB5),
    onSecondary            = Color(0xFF280A20),
    secondaryContainer     = Color(0xFF3D1A34),
    onSecondaryContainer   = Color(0xFFEDD5E8),
    tertiary               = Color(0xFFA8C07A),
    onTertiary             = Color(0xFF162808),
    tertiaryContainer      = Color(0xFF283814),
    onTertiaryContainer    = Color(0xFFC8E0A8),
    error                  = Color(0xFFFF7777),
    onError                = Color(0xFF2A0000),
    errorContainer         = Color(0xFF4A1010),
    onErrorContainer       = Color(0xFFFFBBAA),
    background             = Color(0xFF0F080E),
    onBackground           = Color(0xFFF0D8EC),
    surface                = Color(0xFF1C1019),
    onSurface              = Color(0xFFF0D8EC),
    surfaceVariant         = Color(0xFF2C1A28),
    onSurfaceVariant       = Color(0xFFC8A0BC),
    outline                = Color(0xFF4E2A46),
    outlineVariant         = Color(0xFF3A1A34),
    inverseSurface         = Color(0xFFF0D8EC),
    inverseOnSurface       = Color(0xFF1C1019),
    inversePrimary         = Color(0xFF8A4A20),
    scrim                  = Color(0xFF000000),
)

// ── Standard — light palette ──────────────────────────────────────────────────

private val StandardLight = lightColorScheme(
    primary                = Color(0xFF7A2E55),
    onPrimary              = Color(0xFFFFFFFF),
    primaryContainer       = Color(0xFFF5D0E8),
    onPrimaryContainer     = Color(0xFF42103A),
    secondary              = Color(0xFF7A5018),
    onSecondary            = Color(0xFFFFFFFF),
    secondaryContainer     = Color(0xFFF5E0A8),
    onSecondaryContainer   = Color(0xFF281800),
    tertiary               = Color(0xFF4A7028),
    onTertiary             = Color(0xFFFFFFFF),
    tertiaryContainer      = Color(0xFFD0E8B0),
    onTertiaryContainer    = Color(0xFF162808),
    error                  = Color(0xFFCC0000),
    onError                = Color(0xFFFFFFFF),
    errorContainer         = Color(0xFFFFDAD6),
    onErrorContainer       = Color(0xFF410002),
    background             = Color(0xFFFFF5FA),
    onBackground           = Color(0xFF1A080F),
    surface                = Color(0xFFFFFFFF),
    onSurface              = Color(0xFF1A080F),
    surfaceVariant         = Color(0xFFF5E0EE),
    onSurfaceVariant       = Color(0xFF5A3850),
    outline                = Color(0xFFA07090),
    outlineVariant         = Color(0xFFD8B0CC),
    inverseSurface         = Color(0xFF1A080F),
    inverseOnSurface       = Color(0xFFFFF5FA),
    inversePrimary         = Color(0xFFD4A84E),
    scrim                  = Color(0xFF000000),
)

// ── Belamour — dark palette (deep navy / #2FA1DE cerulean / coral red) ────────

private val BelamourDark = darkColorScheme(
    primary                = Color(0xFF2FA1DE),  // Belamour cerulean blue
    onPrimary              = Color(0xFF001525),  // very dark navy
    primaryContainer       = Color(0xFF0D3B58),  // dark cerulean container
    onPrimaryContainer     = Color(0xFFADE0F8),  // pale sky blue
    secondary              = Color(0xFF82C8E8),  // softer cerulean
    onSecondary            = Color(0xFF001525),
    secondaryContainer     = Color(0xFF0A2E48),
    onSecondaryContainer   = Color(0xFFB8DFF5),
    tertiary               = Color(0xFF80CCD4),  // cool teal
    onTertiary             = Color(0xFF002830),
    tertiaryContainer      = Color(0xFF0A3040),
    onTertiaryContainer    = Color(0xFFB8E5EE),
    error                  = Color(0xFFEF5350),  // coral red
    onError                = Color(0xFF200000),
    errorContainer         = Color(0xFF4A0F0F),
    onErrorContainer       = Color(0xFFFFABAB),
    background             = Color(0xFF0A1520),  // deep navy
    onBackground           = Color(0xFFD0E8FF),  // pale cerulean text
    surface                = Color(0xFF0D1E30),  // dark navy surface
    onSurface              = Color(0xFFD0E8FF),
    surfaceVariant         = Color(0xFF142840),  // slightly lighter navy
    onSurfaceVariant       = Color(0xFF78B8D8),  // muted cerulean
    outline                = Color(0xFF1A5E80),  // dark cerulean border
    outlineVariant         = Color(0xFF134260),
    inverseSurface         = Color(0xFFD0E8FF),
    inverseOnSurface       = Color(0xFF0A1520),
    inversePrimary         = Color(0xFF1683BA),
    scrim                  = Color(0xFF000000),
)

// ── Belamour — light palette ──────────────────────────────────────────────────

private val BelamourLight = lightColorScheme(
    primary                = Color(0xFF1683BA),  // #2FA1DE darkened for light-bg contrast
    onPrimary              = Color(0xFFFFFFFF),
    primaryContainer       = Color(0xFFBBE2F8),  // pale sky blue container
    onPrimaryContainer     = Color(0xFF003A55),
    secondary              = Color(0xFF2077A8),  // medium cerulean
    onSecondary            = Color(0xFFFFFFFF),
    secondaryContainer     = Color(0xFFCCE8F8),
    onSecondaryContainer   = Color(0xFF003850),
    tertiary               = Color(0xFF007A8A),  // teal
    onTertiary             = Color(0xFFFFFFFF),
    tertiaryContainer      = Color(0xFFB2E0E8),
    onTertiaryContainer    = Color(0xFF003540),
    error                  = Color(0xFFD32F2F),  // crimson
    onError                = Color(0xFFFFFFFF),
    errorContainer         = Color(0xFFFFDAD6),
    onErrorContainer       = Color(0xFF410002),
    background             = Color(0xFFE8F5FF),  // pale sky-blue white
    onBackground           = Color(0xFF071525),  // deep navy text
    surface                = Color(0xFFFFFFFF),
    onSurface              = Color(0xFF071525),
    surfaceVariant         = Color(0xFFD0EAF8),  // light cerulean tint
    onSurfaceVariant       = Color(0xFF2A5E7A),  // medium slate
    outline                = Color(0xFF3A7CA8),  // cerulean border
    outlineVariant         = Color(0xFF92C8E8),
    inverseSurface         = Color(0xFF071525),
    inverseOnSurface       = Color(0xFFE8F5FF),
    inversePrimary         = Color(0xFF2FA1DE),
    scrim                  = Color(0xFF000000),
)

// ── Custom theme derivation ───────────────────────────────────────────────────

private fun buildDerivedScheme(theme: AppTheme, darkTheme: Boolean): ColorScheme {
    return if (darkTheme) {
        val bg = Color(theme.darkBackgroundArgb)
        val primary = Color(theme.darkPrimaryArgb)
        val accent = Color(theme.darkAccentArgb)
        val onPrimary = if (primary.luminance() > 0.4f) Color.Black else Color.White
        val onBg = if (bg.luminance() > 0.5f) Color(0xDD000000) else Color(0xDDFFFFFF)
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = blendColors(bg, primary, 0.25f),
            onPrimaryContainer = primary,
            secondary = blendColors(primary, Color.White, 0.15f),
            onSecondary = onPrimary,
            secondaryContainer = blendColors(bg, primary, 0.14f),
            onSecondaryContainer = blendColors(primary, Color.White, 0.2f),
            error = accent,
            onError = if (accent.luminance() > 0.4f) Color.Black else Color.White,
            errorContainer = blendColors(bg, accent, 0.3f),
            onErrorContainer = accent,
            background = bg,
            onBackground = onBg,
            surface = blendColors(bg, Color.White, 0.07f),
            onSurface = onBg,
            surfaceVariant = blendColors(bg, primary, 0.18f),
            onSurfaceVariant = primary.copy(alpha = 0.75f),
            outline = primary.copy(alpha = 0.35f),
            outlineVariant = primary.copy(alpha = 0.18f),
        )
    } else {
        val bg = Color(theme.lightBackgroundArgb)
        val primary = Color(theme.lightPrimaryArgb)
        val accent = Color(theme.lightAccentArgb)
        val onPrimary = if (primary.luminance() > 0.4f) Color.Black else Color.White
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = blendColors(Color.White, primary, 0.15f),
            onPrimaryContainer = blendColors(primary, Color.Black, 0.4f),
            secondary = blendColors(primary, Color.Black, 0.2f),
            onSecondary = Color.White,
            secondaryContainer = blendColors(Color.White, primary, 0.1f),
            onSecondaryContainer = blendColors(primary, Color.Black, 0.4f),
            error = accent,
            onError = Color.White,
            errorContainer = blendColors(Color.White, accent, 0.15f),
            onErrorContainer = blendColors(accent, Color.Black, 0.4f),
            background = bg,
            onBackground = Color(0xDD000000),
            surface = Color.White,
            onSurface = Color(0xDD000000),
            surfaceVariant = blendColors(bg, primary, 0.1f),
            onSurfaceVariant = blendColors(primary, Color.Black, 0.25f),
            outline = primary.copy(alpha = 0.45f),
            outlineVariant = primary.copy(alpha = 0.22f),
        )
    }
}

private fun blendColors(base: Color, overlay: Color, fraction: Float): Color = Color(
    red   = base.red   * (1f - fraction) + overlay.red   * fraction,
    green = base.green * (1f - fraction) + overlay.green * fraction,
    blue  = base.blue  * (1f - fraction) + overlay.blue  * fraction,
    alpha = 1f
)

// ── Public theme composable ───────────────────────────────────────────────────

@Composable
fun WaypointTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appTheme: AppTheme? = null,
    blockColorArgb: Int? = null,
    content: @Composable () -> Unit
) {
    val base: ColorScheme = when {
        appTheme == null || appTheme.id == "standard" -> if (darkTheme) StandardDark else StandardLight
        appTheme.id == "belamour" -> if (darkTheme) BelamourDark else BelamourLight
        else -> buildDerivedScheme(appTheme, darkTheme)
    }
    val colorScheme = if (blockColorArgb != null) {
        // primary must be fully opaque — a block's custom accent color could carry a stray
        // alpha byte (e.g. from before the color pickers forced opacity), and Material3's
        // primary role assumes opaque input; a translucent primary blends unpredictably with
        // whatever's underneath across the whole app.
        val c = blockColorArgb.toOpaqueColor()
        val onPrimary = if (c.luminance() > 0.4f) Color.Black else Color.White
        base.copy(
            primary = c,
            onPrimary = onPrimary,
            primaryContainer = c.copy(alpha = 0.18f),
            onPrimaryContainer = c
        )
    } else base
    MaterialTheme(colorScheme = colorScheme, content = content)
}
