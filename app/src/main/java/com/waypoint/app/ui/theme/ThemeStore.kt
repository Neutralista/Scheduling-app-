package com.waypoint.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ThemeStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("wp_theme", Context.MODE_PRIVATE)
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
        // Force Belamour as the default every time the installed app version changes —
        // fresh install or any update. Detected by comparing the running app's version
        // code against the last one we saw; the user's own selection is left alone for
        // the rest of that version's lifetime and only reset again on the next
        // install/update.
        val currentVersionCode = try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: Exception) { -1L }
        val lastSeenVersionCode = prefs.getLong("last_seen_version_code", -1L)
        if (currentVersionCode != lastSeenVersionCode) {
            prefs.edit()
                .putString("selected_id", "belamour")
                .putLong("last_seen_version_code", currentVersionCode)
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
