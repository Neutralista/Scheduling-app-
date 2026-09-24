package com.waypoint.app.integration

import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.ui.theme.ThemeStore

/**
 * Sends Waypoint's current theme to Might (Training-app) so both apps look the same: the
 * selected theme's colours (built-in or custom) and the dark/light choice. Sent whenever any
 * of those change (ThemeStore) and on every launch, so Might catches up after either app was
 * reinstalled. Might applies it when its theme is set to "Waypoint". Colours only, so there's
 * nothing to protect on the receiving side.
 */
object MightThemeSync {

    const val ACTION_APPLY_WAYPOINT_THEME = "com.neutralista.trainingapp.APPLY_WAYPOINT_THEME"
    private const val TAG = "MightThemeSync"

    fun send(context: Context, store: ThemeStore) {
        runCatching {
            val theme = store.resolveTheme(store.selectedThemeIdFlow.value)
            val darkMode = when (store.darkModeOverrideFlow.value) {
                true -> "dark"
                false -> "light"
                null -> "system"
            }
            context.sendBroadcast(
                Intent(ACTION_APPLY_WAYPOINT_THEME)
                    .setPackage(TRAINING_APP_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra("themeId", theme.id)
                    .putExtra("darkPrimary", theme.darkPrimaryArgb)
                    .putExtra("darkBackground", theme.darkBackgroundArgb)
                    .putExtra("darkAccent", theme.darkAccentArgb)
                    .putExtra("lightPrimary", theme.lightPrimaryArgb)
                    .putExtra("lightBackground", theme.lightBackgroundArgb)
                    .putExtra("lightAccent", theme.lightAccentArgb)
                    .putExtra("darkMode", darkMode)
            )
            AppLogger.i(TAG, "send: theme=${theme.id} darkMode=$darkMode")
        }.onFailure { AppLogger.e(TAG, "send failed", it) }
    }
}
