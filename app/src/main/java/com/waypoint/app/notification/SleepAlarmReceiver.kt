package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SleepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PRE_SLEEP -> {
                val logStore = SleepLogStore(context)
                val bedMs = logStore.getScheduledBedMs()
                val bedText = if (bedMs != null)
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(bedMs))
                else "soon"
                SleepNotificationHelper.sendPreSleepReminder(context, bedText)
            }
            ACTION_BEDTIME -> {
                SleepNotificationHelper.sendBedtimeNotification(context)
                val logStore = SleepLogStore(context)
                if (logStore.getSleepModeState() == SleepModeState.IDLE) {
                    SleepAlarmScheduler.scheduleLateNudge(
                        context,
                        System.currentTimeMillis() + LATE_NUDGE_INTERVAL_MS
                    )
                }
            }
            ACTION_LATE_NUDGE -> {
                val logStore = SleepLogStore(context)
                val now = System.currentTimeMillis()
                val wakeMs = logStore.getScheduledWakeMs()
                if (logStore.getSleepModeState() != SleepModeState.IDLE) return
                if (wakeMs != null && now >= wakeMs) return
                val bedMs = logStore.getScheduledBedMs() ?: now
                val minutesLate = ((now - bedMs) / 60_000L).toInt().coerceAtLeast(0)
                SleepNotificationHelper.sendLateNudge(context, minutesLate)
                SleepAlarmScheduler.scheduleLateNudge(context, now + LATE_NUDGE_INTERVAL_MS)
            }
        }
    }

    companion object {
        const val ACTION_PRE_SLEEP  = "com.waypoint.app.SLEEP_PRE_REMINDER"
        const val ACTION_BEDTIME    = "com.waypoint.app.SLEEP_BEDTIME"
        const val ACTION_LATE_NUDGE = "com.waypoint.app.SLEEP_LATE_NUDGE"

        private const val LATE_NUDGE_INTERVAL_MS = 30 * 60_000L
    }
}
