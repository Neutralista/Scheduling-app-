package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object SleepAlarmScheduler {

    private const val RC_PRE_SLEEP  = 8010
    private const val RC_BEDTIME    = 8011

    fun scheduleAlarms(context: Context, bedMs: Long, wakeMs: Long) {
        cancelAlarms(context)
        val now = System.currentTimeMillis()

        val preSleepMs = bedMs - 30 * 60_000L
        if (preSleepMs > now) scheduleExact(context, preSleepMs, buildPi(context, RC_PRE_SLEEP, SleepAlarmReceiver.ACTION_PRE_SLEEP))
        if (bedMs > now)      scheduleExact(context, bedMs, buildPi(context, RC_BEDTIME, SleepAlarmReceiver.ACTION_BEDTIME))
    }

    fun cancelAlarms(context: Context) {
        cancel(context, RC_PRE_SLEEP,  SleepAlarmReceiver.ACTION_PRE_SLEEP)
        cancel(context, RC_BEDTIME,    SleepAlarmReceiver.ACTION_BEDTIME)
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
