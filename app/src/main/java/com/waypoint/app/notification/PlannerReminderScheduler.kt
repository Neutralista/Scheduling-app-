package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object PlannerReminderScheduler {

    private const val REQUEST_CODE = 9002
    private const val PREFS = "planner_reminder"

    /**
     * Schedules a weekly notification at [dayOfWeek]/[hour]:[minute].
     * [dayOfWeek] uses Java Calendar constants: SUNDAY=1, MONDAY=2, … SATURDAY=7.
     */
    fun scheduleWeekly(context: Context, dayOfWeek: Int, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("day", dayOfWeek)
            .putInt("hour", hour)
            .putInt("minute", minute)
            .apply()
        doSchedule(context, nextOccurrence(dayOfWeek, hour, minute))
    }

    fun rescheduleNext(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = prefs.getInt("day", -1)
        if (day < 0) return
        val hour = prefs.getInt("hour", 16)
        val minute = prefs.getInt("minute", 0)
        doSchedule(context, nextOccurrence(day, hour, minute))
    }

    /** Reschedule the notification to fire [delayMs] from now (default 1 hour). */
    fun snooze(context: Context, delayMs: Long = 3_600_000L) {
        doSchedule(context, System.currentTimeMillis() + delayMs)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(makePendingIntent(context))
    }

    fun sendNow(context: Context) {
        NotificationHelper.sendPlannerReminder(context)
    }

    private fun doSchedule(context: Context, triggerMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) return
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, makePendingIntent(context))
    }

    private fun makePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, PlannerReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun nextOccurrence(dayOfWeek: Int, hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!cal.after(now)) cal.add(Calendar.WEEK_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
