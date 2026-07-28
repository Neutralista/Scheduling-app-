package com.waypoint.app.signal

import android.content.Context
import com.waypoint.app.widget.SignalSources

/**
 * The one shared SignalSources instance for the app. Created once in
 * WaypointApplication and passed to every HabitWidget via
 * HabitWidgetRegistry.register().
 */
class RealSignalSources(context: Context) : SignalSources {
    override val deviceActivity: DeviceActivitySignals = RealDeviceActivitySignals(context)
    override val appUsage: AppUsageSignals = RealAppUsageSignals(context)
    override val healthConnect: HealthConnectSignals = RealHealthConnectSignals(context)
    override val calendar: CalendarSignals = RealCalendarSignals(context)
}
