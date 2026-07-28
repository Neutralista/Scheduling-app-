package com.waypoint.app

import android.app.Application
import com.waypoint.app.signal.RealSignalSources
import com.waypoint.app.widget.HabitWidgetRegistry

/**
 * Bootstrap point. Register your HabitWidget implementations here.
 * signalSources is the one shared instance passed to every widget — it
 * wires up device activity, app usage, and Health Connect signals.
 *
 * The registry ships empty. Uncomment the example below (or add your own)
 * to see something on screen:
 *
 *   HabitWidgetRegistry.register(
 *       ChecklistHabitWidget(id = "example_checklist", displayName = "Simple check-off"),
 *       signalSources
 *   )
 */
class WaypointApplication : Application() {

    lateinit var signalSources: RealSignalSources
        private set

    override fun onCreate() {
        super.onCreate()
        signalSources = RealSignalSources(applicationContext)

        // ── Register your habits here ─────────────────────────────────────
        // import com.waypoint.app.widget.ChecklistHabitWidget
        //
        // HabitWidgetRegistry.register(
        //     ChecklistHabitWidget(
        //         id = "morning_walk",
        //         displayName = "Morning walk"
        //     ),
        //     signalSources
        // )
        // ──────────────────────────────────────────────────────────────────
    }
}
