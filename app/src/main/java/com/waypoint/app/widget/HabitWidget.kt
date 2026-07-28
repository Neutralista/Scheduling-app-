package com.waypoint.app.widget

import androidx.compose.runtime.Composable
import com.waypoint.app.signal.AppUsageSignals
import com.waypoint.app.signal.DeviceActivitySignals
import com.waypoint.app.signal.HealthConnectSignals
import kotlinx.serialization.Serializable

/**
 * One habit "type" the app knows how to run. To add a new kind of habit,
 * implement this interface and register it in HabitWidgetRegistry — nothing
 * else in the app needs to change.
 */
interface HabitWidget {
    val id: String
    val displayName: String

    /** How much space/shape this widget wants — the framework arranges
     *  around this without knowing what's actually inside it. */
    val uiConfig: WidgetUiConfig

    /**
     * Called once when the widget is registered. This is the widget's
     * only connection to the signal sources (Health Connect, device
     * activity, app usage). A widget that doesn't need any of them can
     * leave this as a no-op. Signals are opt-in per widget, not pushed
     * onto every widget automatically.
     */
    fun onAttached(signals: SignalSources) {}

    @Composable
    fun Content(state: WidgetState?, onStateChange: (WidgetState) -> Unit)
}

/**
 * The single bundle of everything a widget might want to read from —
 * Health Connect, device wake/inactivity, per-app usage. Passed once via
 * onAttached so the interface never grows a new parameter every time a new
 * signal source is added.
 */
interface SignalSources {
    val deviceActivity: DeviceActivitySignals
    val appUsage: AppUsageSignals
    val healthConnect: HealthConnectSignals
}

/** Plugin-declared layout shape. Add new sizes/shapes as needed. */
enum class WidgetSize { SMALL_TILE, WIDE_ROW, FULL_CARD }

data class WidgetUiConfig(
    val size: WidgetSize = WidgetSize.WIDE_ROW
)

/**
 * doneToday covers the simple boolean case. values is a generic numeric
 * payload for widgets that need more — a step-count widget stores
 * values["steps"], a screen-time widget stores values["minutes"], etc.
 * Nothing in the framework reads values directly; only the widget that
 * wrote it knows what the keys mean.
 */
@Serializable
data class WidgetState(
    val doneToday: Boolean = false,
    val values: Map<String, Double> = emptyMap()
)
