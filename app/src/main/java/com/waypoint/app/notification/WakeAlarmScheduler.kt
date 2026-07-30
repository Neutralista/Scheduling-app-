package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.waypoint.app.MainActivity

object WakeAlarmScheduler {

    private const val RC_GENTLE = 8020
    private const val RC_MEDIUM = 8021
    private const val RC_FULL   = 8022
    private const val RC_SHOW   = 8023

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
        if (wakeMs   > now) scheduleAlarmClock(context, wakeMs)
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
        if (wakeMs   > now) scheduleAlarmClock(context, wakeMs)
    }

    fun cancelAlarms(context: Context) {
        cancel(context, RC_GENTLE, WakeAlarmReceiver.ACTION_GENTLE)
        cancel(context, RC_MEDIUM, WakeAlarmReceiver.ACTION_MEDIUM)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(buildPi(context, RC_FULL, WakeAlarmReceiver.ACTION_FULL))
    }

    /**
     * Schedules the ringing alarm via setAlarmClock so Android treats it with highest
     * scheduling priority, displays the alarm clock icon in the status bar, and fires
     * reliably through Doze. The PendingIntent starts WakeRingerService which plays the
     * default alarm ringtone on STREAM_ALARM and shows a full-screen wake notification.
     */
    private fun scheduleAlarmClock(context: Context, wakeMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val showIntent = PendingIntent.getActivity(
            context, RC_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(wakeMs, showIntent),
            buildPi(context, RC_FULL, WakeAlarmReceiver.ACTION_FULL)
        )
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
