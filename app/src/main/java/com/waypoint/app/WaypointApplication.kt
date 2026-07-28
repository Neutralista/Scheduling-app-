package com.waypoint.app

import android.app.Application
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.signal.RealSignalSources
import com.waypoint.app.widget.CalendarWidget
import com.waypoint.app.widget.ChecklistHabitWidget
import com.waypoint.app.widget.HabitWidgetRegistry
import com.waypoint.app.widget.StepCountWidget
import com.waypoint.app.widget.WaterTrackerWidget

/**
 * Bootstrap point. All built-in widgets are pre-registered below — open
 * the app and they appear immediately, no extra steps required.
 *
 * To add your own habit, implement HabitWidget and call:
 *   HabitWidgetRegistry.register(YourWidget(id = "unique_id", ...), signalSources)
 */
class WaypointApplication : Application() {

    lateinit var signalSources: RealSignalSources
        private set

    override fun onCreate() {
        super.onCreate()
        signalSources = RealSignalSources(applicationContext)

        NotificationHelper.createChannel(this)
        ReminderScheduler.schedule(this, hourOfDay = 9)

        // ── Built-in widgets ───────────────────────────────────────────────
        HabitWidgetRegistry.register(
            CalendarWidget(id = "calendar", displayName = "Today"),
            signalSources
        )
        HabitWidgetRegistry.register(
            ChecklistHabitWidget(id = "morning_routine", displayName = "Morning routine"),
            signalSources
        )
        HabitWidgetRegistry.register(
            WaterTrackerWidget(id = "water", displayName = "Drink water"),
            signalSources
        )
        HabitWidgetRegistry.register(
            StepCountWidget(id = "steps", displayName = "Step goal"),
            signalSources
        )
        // ──────────────────────────────────────────────────────────────────
    }
}
