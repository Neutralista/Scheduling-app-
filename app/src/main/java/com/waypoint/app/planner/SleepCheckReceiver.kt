package com.waypoint.app.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.waypoint.app.notification.SleepNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SleepCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val logStore = SleepLogStore(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isInteractive = pm.isInteractive

        val wasLogged = logStore.onCheckAlarm(isInteractive)

        val state = logStore.getSleepModeState()
        if (state != SleepModeState.IDLE) {
            if (isInteractive) {
                SleepNotificationHelper.maybeNudge(context, logStore)
            }
            scheduleNextCheck(context)
        }

        // Auto-logged a completed sleep session → write to calendar asynchronously
        if (wasLogged && isInteractive) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val entry = logStore.loadToday()
                    if (entry != null) {
                        SleepCalendarSync.write(context, logStore, entry.bedMillis, entry.wakeMillis, null)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
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
