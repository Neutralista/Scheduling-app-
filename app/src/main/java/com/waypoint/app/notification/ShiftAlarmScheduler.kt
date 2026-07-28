package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ShiftAlarmScheduler {

    private const val REQUEST_CODE = 9001

    fun schedule(context: Context, triggerAtMillis: Long) {
        val pi = buildPendingIntent(context) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel(context: Context) {
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ShiftEndAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
    }

    private fun buildPendingIntent(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ShiftEndAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
