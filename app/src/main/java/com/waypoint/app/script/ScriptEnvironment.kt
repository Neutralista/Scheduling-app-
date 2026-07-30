package com.waypoint.app.script

import android.content.Context
import com.waypoint.app.persistence.SharedMemoryStore
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.signal.AppUsageSignals
import com.waypoint.app.signal.CalendarSignals
import com.waypoint.app.signal.DeviceActivitySignals
import com.waypoint.app.signal.HealthConnectSignals
import com.waypoint.app.signal.WorkScheduleSignals

/**
 * Everything a script can read or write. Replaces the old SignalSources and
 * extends it with sleep store access and script-to-script state.
 *
 * Passed to [AppScript.onAttached] and threaded into the JS evaluation
 * context so user scripts can interact with built-in signals and peer scripts.
 */
interface ScriptEnvironment {
    // ── Android context (for notification scheduling, etc.) ───────────────────
    val context: Context

    // ── Built-in signal sources ───────────────────────────────────────────────
    val deviceActivity: DeviceActivitySignals
    val appUsage: AppUsageSignals
    val healthConnect: HealthConnectSignals
    val calendar: CalendarSignals
    val workSchedule: WorkScheduleSignals
    val eventPlanner: EventPlannerRegistry
    val sleepStore: SleepScheduleStore
    val memory: SharedMemoryStore

    // ── Script-to-script state access ─────────────────────────────────────────
    /** Read another script's persisted state snapshot. Returns null if unknown. */
    fun getScriptState(scriptId: String): ScriptState?

    /** Overwrite another script's persisted state (queued on main dispatcher). */
    fun setScriptState(scriptId: String, state: ScriptState)
}
