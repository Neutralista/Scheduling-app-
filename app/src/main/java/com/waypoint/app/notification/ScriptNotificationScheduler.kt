package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object ScriptNotificationScheduler {

    fun schedule(context: Context, config: NotificationConfig) {
        NotificationStore.save(context, config)
        val weekly = config.weekly ?: return
        val slot = NotificationStore.slot(config.id)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextOccurrence(weekly), alarmPi(context, config.id, slot))
    }

    fun cancel(context: Context, id: String) {
        val slot = NotificationStore.slot(id)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmPi(context, id, slot))
        NotificationStore.remove(context, id)
    }

    fun sendNow(context: Context, config: NotificationConfig) {
        NotificationHelper.sendScriptNotification(context, config)
    }

    fun sendNow(context: Context, id: String) {
        val config = NotificationStore.load(context, id) ?: return
        NotificationHelper.sendScriptNotification(context, config)
    }

    fun snooze(context: Context, id: String, delayMs: Long = 3_600_000L) {
        val slot = NotificationStore.slot(id)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, alarmPi(context, id, slot))
    }

    fun rescheduleWeekly(context: Context, id: String) {
        val config = NotificationStore.load(context, id) ?: return
        val weekly = config.weekly ?: return
        val slot = NotificationStore.slot(id)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextOccurrence(weekly), alarmPi(context, id, slot))
    }

    private fun alarmPi(context: Context, id: String, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            NotificationStore.alarmRequestCode(slot),
            Intent(context, ScriptNotificationReceiver::class.java).apply { putExtra("notif_id", id) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun nextOccurrence(w: WeeklyTrigger): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, w.day)
        cal.set(Calendar.HOUR_OF_DAY, w.hour)
        cal.set(Calendar.MINUTE, w.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.WEEK_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
