package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.cycle.WakeCheckReceiver
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
import com.waypoint.app.planner.SleepScheduleStore
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
                // If the user doesn't manually start Sleep Mode, silently begin inactivity
                // polling anyway so a forgotten-to-arm night still gets detected — bounded to
                // the bedtime→wake window by WakeCheckReceiver/SleepScheduleStore.
                val sleepModeIdle = SleepLogStore(context).getSleepModeState() == SleepModeState.IDLE
                if (sleepModeIdle && SleepScheduleStore(context).isWithinPassiveSleepWindow()) {
                    WakeCheckReceiver.scheduleCheck(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_PRE_SLEEP       = "com.waypoint.app.SLEEP_PRE_REMINDER"
        const val ACTION_BEDTIME         = "com.waypoint.app.SLEEP_BEDTIME"
        const val EXTRA_MINUTES_BEFORE   = "minutes_before"
    }
}
