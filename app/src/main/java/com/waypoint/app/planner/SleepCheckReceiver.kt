package com.waypoint.app.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.waypoint.app.AppLogger
import com.waypoint.app.WaypointApplication
import com.waypoint.app.notification.SleepNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SleepCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val logStore = SleepLogStore(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isInteractive = pm.isInteractive
        val stateBefore = logStore.getSleepModeState()

        AppLogger.i(TAG, "onReceive: isInteractive=$isInteractive state=$stateBefore")

        val wasLogged = logStore.onCheckAlarm(isInteractive)

        val state = logStore.getSleepModeState()
        AppLogger.i(TAG, "onReceive: wasLogged=$wasLogged stateAfter=$state")

        if (state != SleepModeState.IDLE) {
            if (isInteractive) {
                SleepNotificationHelper.maybeNudge(context, logStore)
            }
            scheduleNextCheck(context)
        }

        // Sleep onset detected (MONITORING → SLEEPING): feed the authoritative sleep-start
        // estimate into the cycle tracker. Wake alarm is intentionally left at its anchored time.
        if (wasLogged && !isInteractive) {
            val sleepStartMs = logStore.getSleepStartMillis()
            if (sleepStartMs != null) {
                (context.applicationContext as? WaypointApplication)
                    ?.cycleTracker?.recordSleepAt(sleepStartMs)
            }
        }

        // Auto-logged a completed sleep session → write to calendar and refresh registry.
        if (wasLogged && isInteractive) {
            // Bridge: user is awake and sleep is fully logged — open a new cycle now rather
            // than waiting for ACTION_USER_PRESENT (which may not fire if phone stays unlocked).
            val app = context.applicationContext as? WaypointApplication
            app?.cycleTracker?.recordActive()

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val entry = logStore.loadToday()
                    if (entry != null) {
                        SleepCalendarSync.write(context, logStore, entry.bedMillis, entry.wakeMillis, null)
                    }
                    // Refresh the planner registry so the logged sleep block appears in the
                    // timeline immediately without the user needing to reopen the app.
                    app?.env?.sleepStore?.syncToRegistry(app.env.eventPlanner)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG          = "SleepCheckReceiver"
        private const val ACTION       = "com.waypoint.app.SLEEP_CHECK"
        private const val REQUEST_CODE = 8101

        fun scheduleNextCheck(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildPi(context)
            val triggerAt = System.currentTimeMillis() + 10 * 60_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        fun cancel(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        }

        private fun buildPi(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
