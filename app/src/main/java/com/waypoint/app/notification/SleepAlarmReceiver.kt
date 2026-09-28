package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.SleepCheckReceiver
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
            }
            // Snoozed: again, as "Bedtime in N min" while bedtime's still ahead, else "Time to
            // sleep" — unless Sleep Mode was started meanwhile.
            ACTION_BEDTIME_SNOOZED -> {
                val logStore = SleepLogStore(context)
                if (logStore.getSleepModeState() != SleepModeState.IDLE) return
                val bedMs = logStore.getScheduledBedMs()
                val minutesLeft = bedMs?.let { ((it - System.currentTimeMillis()) / 60_000L).toInt() } ?: 0
                if (bedMs != null && minutesLeft >= 1) {
                    SleepNotificationHelper.sendPreSleepReminder(
                        context, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(bedMs)), minutesLeft
                    )
                } else {
                    SleepNotificationHelper.sendBedtimeNotification(context)
                }
            }
            ACTION_ARM_PASSIVE_DETECTION -> {
                // Scheduled independently of the bedtime notification toggle (see
                // SleepAlarmScheduler) so passive detection still works even if the user turned
                // the "Time to sleep" notification off but left auto-detect on. This arms the
                // *real* sleep-mode state machine (SleepLogStore/SleepCheckReceiver) — the same
                // one "Start Sleep Mode" arms manually — rather than a separate mechanism, so
                // the Monitoring/Sleeping status, calendar logging, and cycle bridging all work
                // exactly as they do when armed by hand.
                try {
                    val logStore = SleepLogStore(context)
                    val alreadyArmed = logStore.getSleepModeState() != SleepModeState.IDLE
                    if (!alreadyArmed && SleepScheduleStore(context).isWithinPassiveSleepWindow()) {
                        logStore.enterSleepMode()
                        SleepCheckReceiver.scheduleNextCheck(context)
                        AppLogger.i(TAG, "ACTION_ARM_PASSIVE_DETECTION: entered MONITORING automatically")
                    }
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "ACTION_ARM_PASSIVE_DETECTION threw", e)
                }
            }
        }
    }

    companion object {
        private const val TAG                  = "SleepAlarmReceiver"
        const val ACTION_PRE_SLEEP             = "com.waypoint.app.SLEEP_PRE_REMINDER"
        const val ACTION_BEDTIME               = "com.waypoint.app.SLEEP_BEDTIME"
        const val ACTION_BEDTIME_SNOOZED       = "com.waypoint.app.SLEEP_BEDTIME_SNOOZED"
        const val ACTION_ARM_PASSIVE_DETECTION = "com.waypoint.app.SLEEP_ARM_PASSIVE_DETECTION"
        const val EXTRA_MINUTES_BEFORE         = "minutes_before"
    }
}
