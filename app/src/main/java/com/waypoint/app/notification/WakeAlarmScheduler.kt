package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import java.util.Calendar

object WakeAlarmScheduler {

    private const val RC_GENTLE   = 8020
    private const val RC_MEDIUM   = 8021
    private const val ALARM_LABEL = "Waypoint Wake"

    /**
     * Full replace: cancel existing alarms then schedule from [wakeMs].
     * Use when the wake time has definitively changed (e.g. user updates settings).
     */
    fun scheduleAlarms(context: Context, wakeMs: Long) {
        cancelAlarms(context)
        val now = System.currentTimeMillis()
        val gentleMs = wakeMs - 15 * 60_000L
        val mediumMs = wakeMs - 10 * 60_000L
        if (gentleMs > now) scheduleExact(context, gentleMs, buildPi(context, RC_GENTLE, WakeAlarmReceiver.ACTION_GENTLE))
        if (mediumMs > now) scheduleExact(context, mediumMs, buildPi(context, RC_MEDIUM, WakeAlarmReceiver.ACTION_MEDIUM))
        if (wakeMs   > now) setSystemAlarm(context, wakeMs)
    }

    /**
     * Safe periodic refresh: only reschedule alarms if the target is still in the future.
     * Does not cancel first, so a background CalendarSyncWorker run that lands within
     * seconds of a pending alarm can't cause it to be dropped.
     */
    fun scheduleAlarmsIfEarlier(context: Context, wakeMs: Long) {
        val now = System.currentTimeMillis()
        val gentleMs = wakeMs - 15 * 60_000L
        val mediumMs = wakeMs - 10 * 60_000L
        if (gentleMs > now) scheduleExact(context, gentleMs, buildPi(context, RC_GENTLE, WakeAlarmReceiver.ACTION_GENTLE))
        if (mediumMs > now) scheduleExact(context, mediumMs, buildPi(context, RC_MEDIUM, WakeAlarmReceiver.ACTION_MEDIUM))
        if (wakeMs   > now) setSystemAlarm(context, wakeMs)
    }

    fun cancelAlarms(context: Context) {
        cancel(context, RC_GENTLE, WakeAlarmReceiver.ACTION_GENTLE)
        cancel(context, RC_MEDIUM, WakeAlarmReceiver.ACTION_MEDIUM)
        cancelSystemAlarm(context)
    }

    /**
     * Creates an alarm in the system Clock app via ACTION_SET_ALARM.
     * EXTRA_SKIP_UI suppresses the Clock app opening. Wrapped in try/catch because
     * background activity starts are blocked on Android 10+ — the next foreground
     * open of the app will retry via syncToRegistry.
     */
    private fun setSystemAlarm(context: Context, wakeMs: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = wakeMs }
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                    putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                    putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) { }
    }

    /**
     * Dismisses the Waypoint Wake alarm in the system Clock app by label.
     * ACTION_DISMISS_ALARM support varies by manufacturer — best-effort only.
     */
    private fun cancelSystemAlarm(context: Context) {
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) { }
    }

    private fun scheduleExact(context: Context, triggerMs: Long, pi: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun cancel(context: Context, reqCode: Int, action: String) {
        val pi = PendingIntent.getBroadcast(
            context, reqCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
    }

    private fun buildPi(context: Context, reqCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, reqCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
