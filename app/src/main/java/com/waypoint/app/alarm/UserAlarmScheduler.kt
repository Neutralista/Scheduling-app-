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
    // Request code layout — sleep uses 8010–8023, shift 9001.
    // Each alarm's base code = RC_BASE + (seq, falling back to a hash for legacy entries) % RC_RANGE;
    // the offsets below stack on top of that base code:
    //   +0                 regular/final trigger
    //   +RC_SHOW_OFFSET      show-intent for setAlarmClock
    //   +RC_GENTLE_OFFSET    staged-wake gentle pulse (optional)
    //   +RC_MEDIUM_OFFSET    staged-wake medium pulse (optional)
    // Snooze re-fires use their own id-hashed slot (RC_SNOOZE_OFFSET) since they're scheduled
    // independently of an alarm's regular occurrence.
    private const val RC_BASE = 9200
    private const val RC_RANGE = 1000
    private const val RC_SHOW_OFFSET   = RC_RANGE
    private const val RC_GENTLE_OFFSET = RC_RANGE * 2
    private const val RC_MEDIUM_OFFSET = RC_RANGE * 3
    private const val RC_SNOOZE_OFFSET = RC_RANGE * 4
    private const val STAGE_GENTLE_MINUTES_BEFORE = 10
    private const val STAGE_MEDIUM_MINUTES_BEFORE = 5

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

    /** Re-arms this alarm at its next occurrence after the immediate next one, skipping it once. */
    fun skipNext(context: Context, alarm: AlarmEntry) {
        if (!alarm.enabled || alarm.repeatDays.isEmpty()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelOne(context, am, alarm)
        val fireMs = nextFireTimeSkippingOne(alarm)
        if (fireMs < 0) return
        scheduleOne(context, am, alarm, fireMs)
    }

    /** Schedules a one-shot snooze re-fire, independent of the alarm's regular schedule. */
    fun scheduleSnooze(context: Context, alarmId: String, label: String, fireMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val rc = snoozeRequestCode(alarmId)
        val showPi = PendingIntent.getActivity(
            context, rc + RC_SHOW_OFFSET,
            Intent(context, AlarmRingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerPi = PendingIntent.getBroadcast(
            context, rc,
            Intent(UserAlarmReceiver.ACTION_FIRE).setPackage(context.packageName)
                .putExtra(UserAlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .putExtra(UserAlarmReceiver.EXTRA_LABEL, label)
                .putExtra(UserAlarmReceiver.EXTRA_SNOOZE_FIRE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(fireMs, showPi), triggerPi)
        AppLogger.i(TAG, "scheduleSnooze: id=$alarmId fireMs=$fireMs")
    }

    private fun scheduleOne(context: Context, am: AlarmManager, alarm: AlarmEntry, fireMsOverride: Long? = null) {
        val fireMs = fireMsOverride ?: nextFireTime(alarm)
        if (fireMs < 0) { AppLogger.w(TAG, "scheduleOne: no fire time for '${alarm.label}'"); return }
        // canScheduleExactAlarms() requires API 31; setAlarmClock() does not need the permission
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        AppLogger.i(TAG, "scheduleOne: id=${alarm.id} time=${alarm.displayTime} fireMs=$fireMs canScheduleExact=$canExact")
        val rc = requestCode(alarm)
        val triggerPi = try {
            buildTriggerPi(context, alarm, rc)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "scheduleOne: buildTriggerPi threw ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
        val showPi = try {
            PendingIntent.getActivity(
                context, rc + RC_SHOW_OFFSET,
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
        if (alarm.stagedWake) scheduleStages(context, am, alarm, rc, fireMs)
    }

    private fun scheduleStages(context: Context, am: AlarmManager, alarm: AlarmEntry, rc: Int, fireMs: Long) {
        val now = System.currentTimeMillis()
        val gentleMs = fireMs - STAGE_GENTLE_MINUTES_BEFORE * 60_000L
        if (gentleMs > now) scheduleStage(context, am, alarm, rc + RC_GENTLE_OFFSET, gentleMs, UserAlarmReceiver.STAGE_GENTLE)
        val mediumMs = fireMs - STAGE_MEDIUM_MINUTES_BEFORE * 60_000L
        if (mediumMs > now) scheduleStage(context, am, alarm, rc + RC_MEDIUM_OFFSET, mediumMs, UserAlarmReceiver.STAGE_MEDIUM)
    }

    private fun scheduleStage(context: Context, am: AlarmManager, alarm: AlarmEntry, rc: Int, triggerMs: Long, stage: String) {
        val pi = PendingIntent.getBroadcast(
            context, rc,
            Intent(UserAlarmReceiver.ACTION_FIRE).setPackage(context.packageName)
                .putExtra(UserAlarmReceiver.EXTRA_ALARM_ID, alarm.id)
                .putExtra(UserAlarmReceiver.EXTRA_LABEL, alarm.label)
                .putExtra(UserAlarmReceiver.EXTRA_STAGE, stage),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun cancelOne(context: Context, am: AlarmManager, alarm: AlarmEntry) {
        val rc = requestCode(alarm)
        cancelPi(context, am, rc)
        cancelPi(context, am, rc + RC_GENTLE_OFFSET)
        cancelPi(context, am, rc + RC_MEDIUM_OFFSET)
        cancelPi(context, am, snoozeRequestCode(alarm.id))
        AppLogger.i(TAG, "cancelled '${alarm.label}'")
    }

    private fun cancelPi(context: Context, am: AlarmManager, rc: Int) {
        val pi = PendingIntent.getBroadcast(
            context, rc,
            Intent(UserAlarmReceiver.ACTION_FIRE).setPackage(context.packageName),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(pi)
    }

    /** Computes the next occurrence at or after [fromMs]. Defaults to "now" for normal scheduling. */
    fun nextFireTime(alarm: AlarmEntry, fromMs: Long = System.currentTimeMillis()): Long {
        val now = Calendar.getInstance().apply { timeInMillis = fromMs }
        val candidate = Calendar.getInstance().apply {
            timeInMillis = fromMs
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

    /** Same as [nextFireTime] but skips past the immediate next occurrence. No-op for one-shots. */
    fun nextFireTimeSkippingOne(alarm: AlarmEntry): Long {
        val first = nextFireTime(alarm)
        if (first < 0 || alarm.repeatDays.isEmpty()) return first
        return nextFireTime(alarm, fromMs = first)
    }

    fun requestCode(alarm: AlarmEntry): Int =
        RC_BASE + (if (alarm.seq >= 0) alarm.seq else alarm.id.hashCode().and(0x7FFF)) % RC_RANGE

    private fun snoozeRequestCode(alarmId: String): Int =
        RC_BASE + RC_SNOOZE_OFFSET + (alarmId.hashCode().and(0x7FFF) % RC_RANGE)

    private fun buildTriggerPi(context: Context, alarm: AlarmEntry, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, rc,
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
