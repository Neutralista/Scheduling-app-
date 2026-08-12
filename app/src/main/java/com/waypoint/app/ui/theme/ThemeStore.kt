package com.waypoint.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ThemeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wp_theme", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val selectedThemeIdFlow = MutableStateFlow(
        prefs.getString("selected_id", "belamour") ?: "belamour"
    )

    // null = follow OS, true = always dark, false = always light
    val darkModeOverrideFlow = MutableStateFlow(
        prefs.getString("dark_mode", null)?.let { it == "dark" }
    )

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "selected_id" -> selectedThemeIdFlow.value = prefs.getString("selected_id", "belamour") ?: "belamour"
            "dark_mode" -> darkModeOverrideFlow.value = prefs.getString("dark_mode", null)?.let { it == "dark" }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Migration v1: force Belamour as the default on first install or first update after this change.
        // Once theme_version >= 1 the user's own selection is never overwritten.
        if (prefs.getInt("theme_version", 0) < 1) {
            prefs.edit()
                .putString("selected_id", "belamour")
                .putInt("theme_version", 1)
                .apply()
        }
    }

    fun selectTheme(id: String) {
        prefs.edit().putString("selected_id", id).apply()
    }

    fun setDarkModeOverride(dark: Boolean?) {
        if (dark == null) prefs.edit().remove("dark_mode").apply()
        else prefs.edit().putString("dark_mode", if (dark) "dark" else "light").apply()
    }

    fun loadCustomThemes(): List<AppTheme> {
        val raw = prefs.getString("custom_themes", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AppTheme>>(raw) }.getOrDefault(emptyList())
    }

    fun saveCustomTheme(theme: AppTheme) {
        val updated = loadCustomThemes().filter { it.id != theme.id } + theme
        prefs.edit().putString("custom_themes", json.encodeToString(updated)).apply()
    }

    fun deleteCustomTheme(id: String) {
        val remaining = loadCustomThemes().filter { it.id != id }
        prefs.edit().putString("custom_themes", json.encodeToString(remaining)).apply()
        if (selectedThemeIdFlow.value == id) selectTheme("belamour")
    }

    fun resolveTheme(id: String): AppTheme =
        BUILT_IN_THEMES.find { it.id == id }
            ?: loadCustomThemes().find { it.id == id }
            ?: STANDARD_THEME
}
