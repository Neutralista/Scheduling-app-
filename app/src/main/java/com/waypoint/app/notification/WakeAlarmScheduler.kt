package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object WakeAlarmScheduler {

    private const val RC_GENTLE = 8020
    private const val RC_MEDIUM = 8021
    private const val RC_FULL   = 8022

    fun scheduleAlarms(context: Context, wakeMs: Long) {
        cancelAlarms(context)
        val now = System.currentTimeMillis()

        val gentleMs = wakeMs - 15 * 60_000L
        val mediumMs = wakeMs - 10 * 60_000L
        val fullMs   = wakeMs -  5 * 60_000L

        if (gentleMs > now) scheduleExact(context, gentleMs, buildPi(context, RC_GENTLE, WakeAlarmReceiver.ACTION_GENTLE))
        if (mediumMs > now) scheduleExact(context, mediumMs, buildPi(context, RC_MEDIUM, WakeAlarmReceiver.ACTION_MEDIUM))
        if (fullMs   > now) scheduleExact(context, fullMs,   buildPi(context, RC_FULL,   WakeAlarmReceiver.ACTION_FULL))
    }

    fun cancelAlarms(context: Context) {
        cancel(context, RC_GENTLE, WakeAlarmReceiver.ACTION_GENTLE)
        cancel(context, RC_MEDIUM, WakeAlarmReceiver.ACTION_MEDIUM)
        cancel(context, RC_FULL,   WakeAlarmReceiver.ACTION_FULL)
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
