package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object WakeAlarmScheduler {

    private const val RC_GENTLE    = 8020
    private const val RC_MEDIUM    = 8021
    private const val RC_RING      = 8022
    private const val RC_RING_SHOW = 8023

    fun scheduleAlarms(context: Context, wakeMs: Long, count: Int = 3, intervalMinutes: Int = 5) {
        cancelAlarms(context)
        scheduleWakeAlarms(context, wakeMs, count, intervalMinutes)
    }

    fun scheduleAlarmsIfEarlier(context: Context, wakeMs: Long, count: Int = 3, intervalMinutes: Int = 5) {
        scheduleWakeAlarms(context, wakeMs, count, intervalMinutes)
    }

    private fun scheduleWakeAlarms(context: Context, wakeMs: Long, count: Int, intervalMinutes: Int) {
        val now = System.currentTimeMillis()
        val intervalMs = intervalMinutes * 60_000L
        if (count >= 3) {
            val gentleMs = wakeMs - 2 * intervalMs
            if (gentleMs > now) scheduleExact(context, gentleMs, buildPi(context, RC_GENTLE, WakeAlarmReceiver.ACTION_GENTLE))
        }
        if (count >= 2) {
            val mediumMs = wakeMs - intervalMs
            if (mediumMs > now) scheduleExact(context, mediumMs, buildPi(context, RC_MEDIUM, WakeAlarmReceiver.ACTION_MEDIUM))
        }
        if (wakeMs > now) scheduleRingAlarm(context, wakeMs)
    }

    fun scheduleRingAlarm(context: Context, wakeMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val showPi = PendingIntent.getActivity(
            context, RC_RING_SHOW,
            Intent(context, AlarmRingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(wakeMs, showPi),
            buildPi(context, RC_RING, WakeAlarmReceiver.ACTION_RING)
        )
    }

    fun cancelAlarms(context: Context) {
        cancel(context, RC_GENTLE, WakeAlarmReceiver.ACTION_GENTLE)
        cancel(context, RC_MEDIUM, WakeAlarmReceiver.ACTION_MEDIUM)
        cancel(context, RC_RING,   WakeAlarmReceiver.ACTION_RING)
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
