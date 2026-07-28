package com.waypoint.app

import android.app.Application
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.signal.RealSignalSources

class WaypointApplication : Application() {

    lateinit var signalSources: RealSignalSources
        private set

    override fun onCreate() {
        super.onCreate()
        signalSources = RealSignalSources(applicationContext)

        NotificationHelper.createChannel(this)
        ReminderScheduler.schedule(this, hourOfDay = 9)
    }
}
