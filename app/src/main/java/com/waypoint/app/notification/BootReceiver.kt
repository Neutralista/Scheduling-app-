package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.WaypointApplication
import com.waypoint.app.alarm.UserAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? WaypointApplication ?: return
        // syncToRegistry does ContentResolver calendar queries — must not run on the main thread.
        // goAsync() holds the wake lock and extends the receiver's life until finish() is called.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.env.sleepStore.syncToRegistry(app.env.eventPlanner)
                UserAlarmScheduler.scheduleAll(context, app.env.alarms.getAll())
                app.scheduleBlockAlarms(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
