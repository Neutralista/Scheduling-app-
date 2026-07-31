package com.waypoint.app.signal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.waypoint.app.AppLogger
import java.util.Calendar

interface ClockAlarmSignals {
    /** Queue bed and wake alarms. Applied the next time [syncFromActivity] is called. */
    fun queueAlarms(bedMs: Long, wakeMs: Long)
    /** Queue cancellation of all alarms. Applied the next time [syncFromActivity] is called. */
    fun queueClearAlarm()
    /**
     * Fire all queued alarms (or dismiss) in the system Clock app.
     * Must be called from a foreground Activity.
     */
    fun syncFromActivity(activity: Activity)
}

class RealClockAlarmSignals(private val context: Context) : ClockAlarmSignals {

    private val prefs get() =
        context.getSharedPreferences("waypoint_clock_alarm", Context.MODE_PRIVATE)

    override fun queueAlarms(bedMs: Long, wakeMs: Long) {
        AppLogger.i(TAG, "queueAlarms: bed=$bedMs wake=$wakeMs")
        prefs.edit()
            .putLong(KEY_BED_MS, bedMs)
            .putLong(KEY_WAKE_MS, wakeMs)
            .putBoolean(KEY_CLEAR, false)
            .apply()
    }

    override fun queueClearAlarm() {
        AppLogger.i(TAG, "queueClearAlarm: will dismiss all alarms on next sync")
        prefs.edit().remove(KEY_BED_MS).remove(KEY_WAKE_MS).putBoolean(KEY_CLEAR, true).apply()
    }

    override fun syncFromActivity(activity: Activity) {
        val clearPending = prefs.getBoolean(KEY_CLEAR, false)
        val storedBedMs = prefs.getLong(KEY_BED_MS, -1L)
        val storedWakeMs = prefs.getLong(KEY_WAKE_MS, -1L)
        val now = System.currentTimeMillis()
        AppLogger.i(TAG, "syncFromActivity: clearPending=$clearPending bed_ms=$storedBedMs wake_ms=$storedWakeMs now=$now")

        if (clearPending) {
            AppLogger.i(TAG, "syncFromActivity: dismissing all Waypoint alarms")
            dismissAlarm(activity, LABEL_PRE_SLEEP)
            dismissAlarm(activity, LABEL_BEDTIME)
            dismissAlarm(activity, LABEL_WAKE)
            prefs.edit().putBoolean(KEY_CLEAR, false).apply()
            return
        }

        val wakeMs = storedWakeMs.takeIf { it != -1L }
        if (wakeMs == null) {
            AppLogger.w(TAG, "syncFromActivity: no alarms queued (wake_ms=-1), nothing to sync")
            return
        }

        val bedMs = storedBedMs.takeIf { it != -1L }

        if (bedMs != null) {
            val preSleepMs = bedMs - 30 * 60_000L
            if (preSleepMs > now) {
                AppLogger.i(TAG, "syncFromActivity: setting pre-sleep alarm at $preSleepMs")
                setAlarm(activity, preSleepMs, LABEL_PRE_SLEEP)
            } else {
                AppLogger.w(TAG, "syncFromActivity: pre-sleep time is in the past, skipping")
            }
            if (bedMs > now) {
                AppLogger.i(TAG, "syncFromActivity: setting bedtime alarm at $bedMs")
                setAlarm(activity, bedMs, LABEL_BEDTIME)
            } else {
                AppLogger.w(TAG, "syncFromActivity: bed time is in the past, skipping")
            }
        }

        if (wakeMs > now) {
            AppLogger.i(TAG, "syncFromActivity: setting wake alarm at $wakeMs")
            setAlarm(activity, wakeMs, LABEL_WAKE)
        } else {
            AppLogger.w(TAG, "syncFromActivity: wake time is in the past ($wakeMs <= $now), skipping")
        }
    }

    private fun setAlarm(context: Context, timeMs: Long, label: String) {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        AppLogger.i(TAG, "setAlarm: hour=$h min=$m label=$label")
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, h)
                    putExtra(AlarmClock.EXTRA_MINUTES, m)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            AppLogger.i(TAG, "setAlarm: success for label=$label")
        } catch (e: Exception) {
            AppLogger.e(TAG, "setAlarm: threw for label=$label", e)
        }
    }

    private fun dismissAlarm(context: Context, label: String) {
        AppLogger.i(TAG, "dismissAlarm: label=$label")
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "dismissAlarm: threw for label=$label", e)
        }
    }

    companion object {
        private const val KEY_BED_MS = "bed_ms"
        private const val KEY_WAKE_MS = "wake_ms"
        private const val KEY_CLEAR = "clear_pending"
        private const val TAG = "ClockAlarm"
        const val LABEL_PRE_SLEEP = "Waypoint Pre-Sleep"
        const val LABEL_BEDTIME = "Waypoint Bedtime"
        const val LABEL_WAKE = "Waypoint Wake"
    }
}
