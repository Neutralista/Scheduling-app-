package com.waypoint.app.script

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable

/**
 * Core unit of the Waypoint extension system. Every piece of functionality —
 * built-in or user-written — is an AppScript.
 *
 * Scripts always have settings (even if empty).
 * Scripts optionally declare a widget via [hasWidget].
 * Scripts can read and write each other's state and the built-in
 * signal sources through the [ScriptEnvironment] passed at call time.
 */
interface AppScript {
    val id: String
    val displayName: String

    /** True when this script renders a visual card in the Widgets window. */
    val hasWidget: Boolean get() = false

    /** True when this script is user-installed (as opposed to built-in). */
    val isUserScript: Boolean get() = false

    /** True when this built-in script's configuration can be reset to factory defaults. */
    val canResetToDefaults: Boolean get() = false

    /** Called once when the script is registered into [ScriptRegistry]. */
    fun onAttached(env: ScriptEnvironment) {}

    /** Reset this script's persisted configuration to factory defaults. No-op for user scripts. */
    suspend fun resetToDefaults() {}

    /** Settings panel shown in the Scripts window. Default: empty. */
    @Composable
    fun SettingsContent() {}

    /**
     * Visual widget shown in the Widgets window.
     * Only called when [hasWidget] is true.
     */
    @Composable
    fun WidgetContent(state: ScriptState?, onStateChange: (ScriptState) -> Unit) {}
}

/**
 * Persisted state for a script. A superset of the old WidgetState:
 *   doneToday  — simple boolean for habit-style completion
 *   values     — numeric payload (steps, minutes, counts …)
 *   settings   — user-configured string values from the settings panel
 */
@Serializable
data class ScriptState(
    val doneToday: Boolean = false,
    val values: Map<String, Double> = emptyMap(),
    val settings: Map<String, String> = emptyMap()
)

/** Layout shape a widget-bearing script requests. */
enum class WidgetSize { SMALL_TILE, WIDE_ROW, FULL_CARD }

data class WidgetUiConfig(val size: WidgetSize = WidgetSize.WIDE_ROW)
