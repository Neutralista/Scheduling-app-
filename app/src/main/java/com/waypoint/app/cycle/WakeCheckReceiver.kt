package com.waypoint.app.cycle

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.waypoint.app.AppLogger
import com.waypoint.app.WaypointApplication
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
import com.waypoint.app.planner.SleepScheduleStore

class WakeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? WaypointApplication ?: return
        AppLogger.i(TAG, "onReceive: action=${intent.action}")
        when (intent.action) {
            Intent.ACTION_USER_PRESENT -> {
                app.cycleTracker.updateLastActive()
                app.cycleTracker.recordActive()
            }
            ACTION_WAKE_CHECK -> {
                // Run the inactivity check while sleep mode is actively armed, or — even if it
                // isn't — while we're still inside the bedtime→wake passive-detection window.
                // Cancel otherwise so this doesn't keep firing through the rest of the day.
                val sleepModeActive = SleepLogStore(context).getSleepModeState() != SleepModeState.IDLE
                val withinPassiveWindow = SleepScheduleStore(context).isWithinPassiveSleepWindow()
                if (sleepModeActive || withinPassiveWindow) {
                    app.cycleTracker.checkInactivity()
                } else {
                    AppLogger.i(TAG, "onReceive: WAKE_CHECK outside active/passive window — cancelling")
                    cancel(context)
                }
            }
        }
    }

    companion object {
        private const val TAG       = "WakeCheckReceiver"
        const val ACTION_WAKE_CHECK = "com.waypoint.app.WAKE_CHECK"
        private const val RC        = 9200
        private const val INTERVAL  = 10 * 60_000L  // 10 minutes

        fun scheduleCheck(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildPi(context)
            val at = System.currentTimeMillis() + INTERVAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        fun cancel(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context, RC,
                Intent(ACTION_WAKE_CHECK).setPackage(context.packageName),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        }

        private fun buildPi(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, RC,
                Intent(ACTION_WAKE_CHECK).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
