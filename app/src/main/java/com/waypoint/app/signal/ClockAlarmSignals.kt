package com.waypoint.app.signal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.waypoint.app.AppLogger
import java.util.Calendar

interface ClockAlarmSignals {
    /** Queue the wake time. Three Clock alarms are set the next time [syncFromActivity] is called. */
    fun queueWakeAlarm(wakeMs: Long)
    /** Queue cancellation of all wake alarms. Applied the next time [syncFromActivity] is called. */
    fun queueClearAlarm()
    /**
     * Fire all queued wake alarms (or dismiss) in the system Clock app.
     * Must be called from a foreground Activity.
     */
    fun syncFromActivity(activity: Activity)
}

class RealClockAlarmSignals(private val context: Context) : ClockAlarmSignals {

    private val prefs get() =
        context.getSharedPreferences("waypoint_clock_alarm", Context.MODE_PRIVATE)

    override fun queueWakeAlarm(wakeMs: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = wakeMs }
        val timeStr = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        AppLogger.i(TAG, "queueWakeAlarm: $timeStr (epoch $wakeMs)")
        prefs.edit().putLong(KEY_WAKE_MS, wakeMs).putBoolean(KEY_CLEAR, false).apply()
    }

    override fun queueClearAlarm() {
        AppLogger.i(TAG, "queueClearAlarm: will dismiss all alarms on next sync")
        prefs.edit().remove(KEY_WAKE_MS).putBoolean(KEY_CLEAR, true).apply()
    }

    override fun syncFromActivity(activity: Activity) {
        val clearPending = prefs.getBoolean(KEY_CLEAR, false)
        val storedWakeMs = prefs.getLong(KEY_WAKE_MS, -1L)
        val now = System.currentTimeMillis()
        AppLogger.i(TAG, "syncFromActivity: clearPending=$clearPending wake_ms=$storedWakeMs now=$now")

        // Always clean up labels from older versions of this integration
        dismissAlarm(activity, LABEL_STALE_PRE_SLEEP)
        dismissAlarm(activity, LABEL_STALE_BEDTIME)
        dismissAlarm(activity, LABEL_STALE_WAKE)

        if (clearPending) {
            AppLogger.i(TAG, "syncFromActivity: dismissing all Waypoint wake alarms")
            dismissAlarm(activity, LABEL_GENTLE)
            dismissAlarm(activity, LABEL_ALARM)
            dismissAlarm(activity, LABEL_RING)
            prefs.edit().putBoolean(KEY_CLEAR, false).apply()
            return
        }

        val wakeMs = storedWakeMs.takeIf { it != -1L }
        if (wakeMs == null) {
            AppLogger.w(TAG, "syncFromActivity: no wake time queued (wake_ms=-1), nothing to sync")
            return
        }

        val gentleMs = wakeMs - 15 * 60_000L
        val alarmMs  = wakeMs - 10 * 60_000L

        if (gentleMs > now) {
            AppLogger.i(TAG, "syncFromActivity: setting gentle alarm at $gentleMs")
            setAlarm(activity, gentleMs, LABEL_GENTLE)
        } else {
            AppLogger.w(TAG, "syncFromActivity: gentle time in the past, skipping")
        }
        if (alarmMs > now) {
            AppLogger.i(TAG, "syncFromActivity: setting alarm at $alarmMs")
            setAlarm(activity, alarmMs, LABEL_ALARM)
        } else {
            AppLogger.w(TAG, "syncFromActivity: alarm time in the past, skipping")
        }
        if (wakeMs > now) {
            AppLogger.i(TAG, "syncFromActivity: setting ring alarm at $wakeMs")
            setAlarm(activity, wakeMs, LABEL_RING)
        } else {
            AppLogger.w(TAG, "syncFromActivity: wake time in the past ($wakeMs <= $now), skipping")
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
        private const val KEY_WAKE_MS = "wake_ms"
        private const val KEY_CLEAR = "clear_pending"
        private const val TAG = "ClockAlarm"
        const val LABEL_GENTLE = "Waypoint Gentle"
        const val LABEL_ALARM  = "Waypoint Alarm"
        const val LABEL_RING   = "Waypoint Ring"
        // Labels from earlier versions of this integration — cleaned up on every sync
        private const val LABEL_STALE_PRE_SLEEP = "Waypoint Pre-Sleep"
        private const val LABEL_STALE_BEDTIME   = "Waypoint Bedtime"
        private const val LABEL_STALE_WAKE      = "Waypoint Wake"
    }
}
