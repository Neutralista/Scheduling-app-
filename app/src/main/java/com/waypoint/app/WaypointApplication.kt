package com.waypoint.app

import android.app.Application
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.planner.EventCondition
import com.waypoint.app.planner.PlannerEvent
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

        // ── Sample planner events ──────────────────────────────────────────
        // Normally plugins register their own events in onAttached() via
        // signals.eventPlanner.register(...). These built-in samples show
        // how conditions work: time windows, shift constraints, and day types.
        signalSources.eventPlanner.register(
            PlannerEvent(
                id = "morning_exercise",
                title = "Morning exercise",
                durationMinutes = 30,
                priority = 8,
                conditions = listOf(EventCondition.TimeWindow(6, 0, 9, 30))
            )
        )
        signalSources.eventPlanner.register(
            PlannerEvent(
                id = "grocery_run",
                title = "Grocery run",
                durationMinutes = 45,
                priority = 6,
                conditions = listOf(
                    EventCondition.NotDuringShift,
                    // Store open 09:00–20:00
                    EventCondition.TimeWindow(9, 0, 20, 0)
                )
            )
        )
        signalSources.eventPlanner.register(
            PlannerEvent(
                id = "evening_walk",
                title = "Evening walk",
                durationMinutes = 20,
                priority = 5,
                conditions = listOf(EventCondition.TimeWindow(17, 0, 21, 0))
            )
        )
        signalSources.eventPlanner.register(
            PlannerEvent(
                id = "weekend_reading",
                title = "Weekend reading",
                durationMinutes = 60,
                priority = 4,
                conditions = listOf(EventCondition.DayOffOnly)
            )
        )
        // ──────────────────────────────────────────────────────────────────
    }
}
