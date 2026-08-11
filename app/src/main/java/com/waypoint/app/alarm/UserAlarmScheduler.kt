package com.waypoint.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.AlarmRingActivity
import java.util.Calendar

object UserAlarmScheduler {

    private const val TAG = "UserAlarmScheduler"
    // Request code range 9200–10199; sleep uses 8020–8023, shift 9001
    private const val RC_BASE = 9200
    private const val RC_SHOW_OFFSET = 1000

    fun scheduleAll(context: Context, alarms: List<AlarmEntry>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (alarm in alarms) {
            cancelOne(context, am, alarm)
            if (alarm.enabled) scheduleOne(context, am, alarm)
        }
    }

    fun schedule(context: Context, alarm: AlarmEntry) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelOne(context, am, alarm)
        if (alarm.enabled) scheduleOne(context, am, alarm)
    }

    fun cancel(context: Context, alarm: AlarmEntry) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelOne(context, am, alarm)
    }

    private fun scheduleOne(context: Context, am: AlarmManager, alarm: AlarmEntry) {
        val fireMs = nextFireTime(alarm)
        if (fireMs < 0) { AppLogger.w(TAG, "scheduleOne: no fire time for '${alarm.label}'"); return }
        // canScheduleExactAlarms() requires API 31; setAlarmClock() does not need the permission
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        AppLogger.i(TAG, "scheduleOne: id=${alarm.id} time=${alarm.displayTime} fireMs=$fireMs canScheduleExact=$canExact")
        val triggerPi = try {
            buildTriggerPi(context, alarm)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "scheduleOne: buildTriggerPi threw ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
        val showPi = try {
            PendingIntent.getActivity(
                context, requestCode(alarm.id) + RC_SHOW_OFFSET,
                Intent(context, AlarmRingActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (e: Throwable) {
            AppLogger.e(TAG, "scheduleOne: getActivity threw ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireMs, showPi), triggerPi)
            AppLogger.i(TAG, "scheduled '${alarm.label}' ${alarm.displayTime} repeat=${alarm.repeatDays} fireMs=$fireMs")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "scheduleOne: setAlarmClock threw ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
    }

    private fun cancelOne(context: Context, am: AlarmManager, alarm: AlarmEntry) {
        val pi = PendingIntent.getBroadcast(
            context, requestCode(alarm.id),
            Intent(UserAlarmReceiver.ACTION_FIRE).setPackage(context.packageName)
                .putExtra(UserAlarmReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(pi)
        AppLogger.i(TAG, "cancelled '${alarm.label}'")
    }

    fun nextFireTime(alarm: AlarmEntry): Long {
        val now = Calendar.getInstance()
        val candidate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (candidate.timeInMillis <= now.timeInMillis) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (alarm.repeatDays.isEmpty()) return candidate.timeInMillis

        repeat(7) {
            val isoDay = isoDay(candidate.get(Calendar.DAY_OF_WEEK))
            if (isoDay in alarm.repeatDays) return candidate.timeInMillis
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return -1L
    }

    fun requestCode(id: String): Int = RC_BASE + (id.hashCode().and(0x7FFF) % 1000)

    private fun buildTriggerPi(context: Context, alarm: AlarmEntry): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode(alarm.id),
            Intent(UserAlarmReceiver.ACTION_FIRE).setPackage(context.packageName)
                .putExtra(UserAlarmReceiver.EXTRA_ALARM_ID, alarm.id)
                .putExtra(UserAlarmReceiver.EXTRA_LABEL, alarm.label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun isoDay(calDay: Int): Int = when (calDay) {
        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
        else -> 7
    }
}
