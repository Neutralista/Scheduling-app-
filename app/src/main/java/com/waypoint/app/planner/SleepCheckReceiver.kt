package com.waypoint.app.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.SleepNotificationHelper
import com.waypoint.app.notification.WakeAlarmScheduler
import com.waypoint.app.signal.RealWorkScheduleSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

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

        // Sleep onset detected (MONITORING → SLEEPING): try to extend wake if sleep came early
        if (wasLogged && !isInteractive) {
            AppLogger.i(TAG, "onReceive: sleep onset detected, checking if wake can be pushed")
            adjustWakeForSleepOnset(context, logStore)
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

    private fun adjustWakeForSleepOnset(context: Context, logStore: SleepLogStore) {
        val sleepStartMs = logStore.getSleepStartMillis() ?: run {
            AppLogger.w(TAG, "adjustWake: sleepStartMs null")
            return
        }
        val currentWakeMs = logStore.getScheduledWakeMs() ?: run {
            AppLogger.w(TAG, "adjustWake: scheduledWakeMs null, cannot adjust")
            return
        }

        val sleepStore = SleepScheduleStore(context)
        val s = sleepStore.load()
        val idealWakeMs = sleepStartMs + s.targetSleepMinutes * 60_000L

        AppLogger.i(TAG, "adjustWake: sleepStart=$sleepStartMs targetMin=${s.targetSleepMinutes} " +
                "idealWake=$idealWakeMs currentWake=$currentWakeMs")

        if (idealWakeMs <= currentWakeMs) {
            AppLogger.i(TAG, "adjustWake: ideal wake is not later than current, no adjustment")
            return
        }

        // Ceiling = shiftStart − morningBuffer on the calendar day the wake alarm is set for
        val ws = RealWorkScheduleSignals(context)
        val wakeDayCal = Calendar.getInstance().apply { timeInMillis = currentWakeMs }
        val wakeSchedule = ws.getSchedule(wakeDayCal)

        val ceilingMs: Long = if (wakeSchedule.isWork && wakeSchedule.shiftStart != null) {
            val latestWakeMin = wakeSchedule.shiftStart.totalMinutes - s.minMorningBufferMinutes
            if (latestWakeMin <= 0) {
                AppLogger.w(TAG, "adjustWake: morning buffer exceeds shift start, no room")
                return
            }
            Calendar.getInstance().apply {
                timeInMillis = currentWakeMs
                set(Calendar.HOUR_OF_DAY, latestWakeMin / 60)
                set(Calendar.MINUTE, latestWakeMin % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else {
            Long.MAX_VALUE
        }

        val newWakeMs = minOf(idealWakeMs, ceilingMs)
        if (newWakeMs <= currentWakeMs) {
            AppLogger.i(TAG, "adjustWake: ceiling=$ceilingMs caps new wake at $newWakeMs, no gain")
            return
        }

        AppLogger.i(TAG, "adjustWake: rescheduling wake $currentWakeMs → $newWakeMs (ceiling=$ceilingMs)")
        WakeAlarmScheduler.scheduleAlarms(context, newWakeMs)
        val bedMs = logStore.getScheduledBedMs() ?: (sleepStartMs - s.targetSleepMinutes * 60_000L)
        logStore.updateScheduledTimes(bedMs, newWakeMs)
        SleepNotificationHelper.showAlarmStatus(context, bedMs, newWakeMs)
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
