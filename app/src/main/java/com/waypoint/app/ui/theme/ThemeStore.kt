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
        prefs.getString("selected_id", "standard") ?: "standard"
    )

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "selected_id") {
            selectedThemeIdFlow.value = prefs.getString("selected_id", "standard") ?: "standard"
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun selectTheme(id: String) {
        prefs.edit().putString("selected_id", id).apply()
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
        if (selectedThemeIdFlow.value == id) selectTheme("standard")
    }

    fun resolveTheme(id: String): AppTheme =
        BUILT_IN_THEMES.find { it.id == id }
            ?: loadCustomThemes().find { it.id == id }
            ?: STANDARD_THEME
}
