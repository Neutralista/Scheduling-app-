package com.waypoint.app.signal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.waypoint.app.AppLogger
import java.util.Calendar

interface ClockAlarmSignals {
    /** Queue a wake alarm. The Clock app alarm is set the next time [syncFromActivity] is called. */
    fun queueWakeAlarm(wakeMs: Long)
    /** Queue cancellation of the wake alarm. Applied the next time [syncFromActivity] is called. */
    fun queueClearAlarm()
    /**
     * Fire the queued alarm (or dismiss) in the system Clock app.
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
        AppLogger.i(TAG, "queueClearAlarm: sleep disabled, will dismiss on next sync")
        prefs.edit().remove(KEY_WAKE_MS).putBoolean(KEY_CLEAR, true).apply()
    }

    override fun syncFromActivity(activity: Activity) {
        val clearPending = prefs.getBoolean(KEY_CLEAR, false)
        val storedWakeMs = prefs.getLong(KEY_WAKE_MS, -1L)
        val now = System.currentTimeMillis()
        AppLogger.i(TAG, "syncFromActivity: clearPending=$clearPending wake_ms=$storedWakeMs now=$now")

        if (clearPending) {
            AppLogger.i(TAG, "syncFromActivity: firing DISMISS_ALARM")
            dismissAlarm(activity)
            prefs.edit().putBoolean(KEY_CLEAR, false).apply()
            return
        }
        val wakeMs = storedWakeMs.takeIf { it != -1L }
        if (wakeMs == null) {
            AppLogger.w(TAG, "syncFromActivity: no wake time queued (wake_ms=-1), nothing to sync")
            return
        }
        if (wakeMs <= now) {
            AppLogger.w(TAG, "syncFromActivity: wake time is in the past ($wakeMs <= $now), skipping")
            return
        }
        AppLogger.i(TAG, "syncFromActivity: firing SET_ALARM for wake_ms=$wakeMs")
        setAlarm(activity, wakeMs)
    }

    private fun setAlarm(context: Context, wakeMs: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = wakeMs }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        AppLogger.i(TAG, "setAlarm: startActivity ACTION_SET_ALARM hour=$h min=$m label=$ALARM_LABEL")
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, h)
                    putExtra(AlarmClock.EXTRA_MINUTES, m)
                    putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            AppLogger.i(TAG, "setAlarm: startActivity returned without exception")
        } catch (e: Exception) {
            AppLogger.e(TAG, "setAlarm: startActivity threw", e)
        }
    }

    private fun dismissAlarm(context: Context) {
        AppLogger.i(TAG, "dismissAlarm: startActivity ACTION_DISMISS_ALARM label=$ALARM_LABEL")
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "dismissAlarm: startActivity threw", e)
        }
    }

    companion object {
        private const val KEY_WAKE_MS = "wake_ms"
        private const val KEY_CLEAR = "clear_pending"
        private const val TAG = "ClockAlarm"
        const val ALARM_LABEL = "Waypoint Wake"
    }
}
