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

val OCEAN_THEME = AppTheme(
    id = "ocean",
    name = "Ocean",
    isBuiltIn = true,
    darkPrimaryArgb = 0xFF4FC3F7.toInt(),
    darkBackgroundArgb = 0xFF0B1623.toInt(),
    darkAccentArgb = 0xFFEF5350.toInt(),
    lightPrimaryArgb = 0xFF1565C0.toInt(),
    lightBackgroundArgb = 0xFFEFF5FF.toInt(),
    lightAccentArgb = 0xFFD32F2F.toInt(),
)

val BUILT_IN_THEMES: List<AppTheme> = listOf(STANDARD_THEME, OCEAN_THEME)
