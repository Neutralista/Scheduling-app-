package com.waypoint.app

import android.app.Application
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.signal.RealSignalSources
import com.waypoint.app.widget.HabitWidgetRegistry
import com.waypoint.app.widget.ScriptedWidgetStore

class WaypointApplication : Application() {

    lateinit var signalSources: RealSignalSources
        private set
    lateinit var sleepScheduleStore: SleepScheduleStore
        private set
    lateinit var scriptedWidgetStore: ScriptedWidgetStore
        private set

    override fun onCreate() {
        super.onCreate()
        signalSources = RealSignalSources(applicationContext)
        sleepScheduleStore = SleepScheduleStore(applicationContext)
        sleepScheduleStore.syncToRegistry(signalSources.eventPlanner, signalSources.workSchedule)
        scriptedWidgetStore = ScriptedWidgetStore(applicationContext)

        // Re-register any scripted widgets the user added in a previous session
        scriptedWidgetStore.loadAll().forEach { widget ->
            HabitWidgetRegistry.register(widget, signalSources)
        }

        NotificationHelper.createChannel(this)
        NotificationHelper.createShiftChannel(this)
        ReminderScheduler.schedule(this, hourOfDay = 9)
    }
}
