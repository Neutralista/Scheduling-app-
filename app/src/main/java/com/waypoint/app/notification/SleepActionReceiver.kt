package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.os.Build
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.SleepCheckReceiver
import com.waypoint.app.planner.SleepLogStore

class SleepActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.i(TAG, "onReceive: action=${intent.action}")
        when (intent.action) {
            ACTION_ENTER_SLEEP_MODE -> {
                val logStore = SleepLogStore(context)
                val prevState = logStore.getSleepModeState()
                logStore.enterSleepMode()
                AppLogger.i(TAG, "enterSleepMode: prevState=$prevState → MONITORING")
                SleepCheckReceiver.scheduleNextCheck(context)
                AppLogger.i(TAG, "scheduleNextCheck: done")
                // Gone to sleep: the reminders (and a snoozed one) are done with.
                SleepNotificationHelper.clearBedtimeReminders(context)
                cancelSnooze(context)
            }
            ACTION_SNOOZE_BEDTIME -> {
                SleepNotificationHelper.clearBedtimeReminders(context)
                val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
                val am = context.getSystemService(AlarmManager::class.java)
                val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, snoozePi(context))
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, snoozePi(context))
                AppLogger.i(TAG, "bedtime reminder snoozed $SNOOZE_MINUTES min")
            }
        }
    }

    companion object {
        private const val TAG = "SleepActionReceiver"
        const val ACTION_ENTER_SLEEP_MODE = "com.waypoint.app.ENTER_SLEEP_MODE"
        const val ACTION_SNOOZE_BEDTIME = "com.waypoint.app.SNOOZE_BEDTIME"
        const val SNOOZE_MINUTES = 15

        private fun snoozePi(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 204,
            Intent(context, SleepAlarmReceiver::class.java).setAction(SleepAlarmReceiver.ACTION_BEDTIME_SNOOZED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun cancelSnooze(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(snoozePi(context))
        }
    }
}
