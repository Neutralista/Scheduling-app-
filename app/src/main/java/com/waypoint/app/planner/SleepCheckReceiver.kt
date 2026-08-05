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
import com.waypoint.app.notification.WakeAlarmScheduler
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

        // Sleep onset detected (MONITORING → SLEEPING): try to extend wake if sleep came early
        if (wasLogged && !isInteractive) {
            AppLogger.i(TAG, "onReceive: sleep onset detected, checking if wake can be pushed")
            adjustWakeForSleepOnset(context, logStore)
            // Bridge: feed the authoritative sleep-start estimate into the cycle tracker
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
        val now = System.currentTimeMillis()

        AppLogger.i(TAG, "adjustWake: sleepStart=$sleepStartMs targetMin=${s.targetSleepMinutes} " +
                "idealWake=$idealWakeMs currentWake=$currentWakeMs")

        // Skip if ideal wake is already past or less than 30 min away — alarm would be useless
        if (idealWakeMs <= now + 30 * 60_000L) {
            AppLogger.i(TAG, "adjustWake: ideal wake too close or in the past, skipping")
            return
        }

        val ceilingMs: Long = Long.MAX_VALUE

        val newWakeMs = minOf(idealWakeMs, ceilingMs)

        // If we'd be moving wake later but the shift ceiling blocks it below current, no benefit
        if (idealWakeMs > currentWakeMs && newWakeMs <= currentWakeMs) {
            AppLogger.i(TAG, "adjustWake: ceiling=$ceilingMs caps later adjustment below current, skipping")
            return
        }

        if (newWakeMs == currentWakeMs) {
            AppLogger.i(TAG, "adjustWake: no change needed")
            return
        }

        val direction = if (newWakeMs > currentWakeMs) "later" else "earlier"
        AppLogger.i(TAG, "adjustWake: rescheduling wake $direction: $currentWakeMs → $newWakeMs (ceiling=$ceilingMs)")
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
