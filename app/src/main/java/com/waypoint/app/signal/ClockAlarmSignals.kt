package com.waypoint.app.signal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
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
        prefs.edit().putLong(KEY_WAKE_MS, wakeMs).putBoolean(KEY_CLEAR, false).apply()
    }

    override fun queueClearAlarm() {
        prefs.edit().remove(KEY_WAKE_MS).putBoolean(KEY_CLEAR, true).apply()
    }

    override fun syncFromActivity(activity: Activity) {
        if (prefs.getBoolean(KEY_CLEAR, false)) {
            dismissAlarm(activity)
            prefs.edit().putBoolean(KEY_CLEAR, false).apply()
            return
        }
        val wakeMs = prefs.getLong(KEY_WAKE_MS, -1L).takeIf { it != -1L } ?: return
        if (wakeMs > System.currentTimeMillis()) setAlarm(activity, wakeMs)
    }

    private fun setAlarm(context: Context, wakeMs: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = wakeMs }
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                    putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                    putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) { }
    }

    private fun dismissAlarm(context: Context) {
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) { }
    }

    companion object {
        private const val KEY_WAKE_MS = "wake_ms"
        private const val KEY_CLEAR = "clear_pending"
        const val ALARM_LABEL = "Waypoint Wake"
    }
}
