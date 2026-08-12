package com.waypoint.app.ui.theme

import kotlinx.serialization.Serializable

@Serializable
data class AppTheme(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean = false,
    val darkPrimaryArgb: Int = 0,
    val darkBackgroundArgb: Int = 0,
    val darkAccentArgb: Int = 0,
    val lightPrimaryArgb: Int = 0,
    val lightBackgroundArgb: Int = 0,
    val lightAccentArgb: Int = 0,
)

val STANDARD_THEME = AppTheme(
    id = "standard",
    name = "Standard",
    isBuiltIn = true,
    darkPrimaryArgb = 0xFFD4A84E.toInt(),
    darkBackgroundArgb = 0xFF0F080E.toInt(),
    darkAccentArgb = 0xFFFF7777.toInt(),
    lightPrimaryArgb = 0xFF7A2E55.toInt(),
    lightBackgroundArgb = 0xFFFFF5FA.toInt(),
    lightAccentArgb = 0xFFCC0000.toInt(),
)

val BELAMOUR_THEME = AppTheme(
    id = "belamour",
    name = "Belamour",
    isBuiltIn = true,
    darkPrimaryArgb = 0xFF2FA1DE.toInt(),    // Belamour sky blue
    darkBackgroundArgb = 0xFF0A1520.toInt(), // deep navy
    darkAccentArgb = 0xFFEF5350.toInt(),     // coral red
    lightPrimaryArgb = 0xFF1683BA.toInt(),   // Belamour blue darkened for light-bg contrast
    lightBackgroundArgb = 0xFFE8F5FF.toInt(),// pale sky-blue white
    lightAccentArgb = 0xFFD32F2F.toInt(),    // crimson
)

val BUILT_IN_THEMES: List<AppTheme> = listOf(STANDARD_THEME, BELAMOUR_THEME)
