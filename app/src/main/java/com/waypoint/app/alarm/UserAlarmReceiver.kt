package com.waypoint.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.AlarmRingService
import com.waypoint.app.notification.SleepNotificationHelper

class UserAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Alarm"
        AppLogger.i(TAG, "onReceive: id=$id label='$label'")

        try {
            val store = AlarmStore(context)
            val alarm = store.loadAll().firstOrNull { it.id == id }
            if (alarm != null) {
                if (alarm.repeatDays.isNotEmpty()) {
                    UserAlarmScheduler.schedule(context, alarm)
                } else {
                    store.setEnabledSync(id, false)
                }
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "onReceive: store/reschedule threw ${e.javaClass.name}: ${e.message}", e)
        }

        context.startForegroundService(
            Intent(context, AlarmRingService::class.java).apply {
                action = AlarmRingService.ACTION_RING
                putExtra(AlarmRingService.EXTRA_VOLUME, 1.0f)
                putExtra(AlarmRingService.EXTRA_CHANNEL, SleepNotificationHelper.CH_WAKE_FULL)
                putExtra(AlarmRingService.EXTRA_TITLE, label)
                putExtra(AlarmRingService.EXTRA_FULL_SCREEN, true)
            }
        )
    }

    companion object {
        const val ACTION_FIRE    = "com.waypoint.app.USER_ALARM_FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_LABEL    = "label"
        const val EXTRA_VIBRATE  = "vibrate"
        private const val TAG    = "UserAlarmReceiver"
    }
}
