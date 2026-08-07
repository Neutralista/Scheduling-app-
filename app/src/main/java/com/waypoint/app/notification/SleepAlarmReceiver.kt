package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.planner.SleepLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SleepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PRE_SLEEP -> {
                val minutesBefore = intent.getIntExtra(EXTRA_MINUTES_BEFORE, 30)
                val logStore = SleepLogStore(context)
                val bedMs = logStore.getScheduledBedMs()
                val bedText = if (bedMs != null)
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(bedMs))
                else "soon"
                SleepNotificationHelper.sendPreSleepReminder(context, bedText, minutesBefore)
            }
            ACTION_BEDTIME -> {
                SleepNotificationHelper.sendBedtimeNotification(context)
            }
        }
    }

    companion object {
        const val ACTION_PRE_SLEEP       = "com.waypoint.app.SLEEP_PRE_REMINDER"
        const val ACTION_BEDTIME         = "com.waypoint.app.SLEEP_BEDTIME"
        const val EXTRA_MINUTES_BEFORE   = "minutes_before"
    }
}
