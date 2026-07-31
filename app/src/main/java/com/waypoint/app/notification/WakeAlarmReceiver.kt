package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = when (intent.action) {
            ACTION_GENTLE -> Intent(context, AlarmRingService::class.java).apply {
                action = AlarmRingService.ACTION_RING
                putExtra(AlarmRingService.EXTRA_VOLUME, 0.35f)
                putExtra(AlarmRingService.EXTRA_CHANNEL, SleepNotificationHelper.CH_WAKE_GENTLE)
                putExtra(AlarmRingService.EXTRA_TITLE, "Wake up soon")
                putExtra(AlarmRingService.EXTRA_FULL_SCREEN, false)
            }
            ACTION_MEDIUM -> Intent(context, AlarmRingService::class.java).apply {
                action = AlarmRingService.ACTION_RING
                putExtra(AlarmRingService.EXTRA_VOLUME, 0.7f)
                putExtra(AlarmRingService.EXTRA_CHANNEL, SleepNotificationHelper.CH_WAKE_MEDIUM)
                putExtra(AlarmRingService.EXTRA_TITLE, "Almost time to wake up")
                putExtra(AlarmRingService.EXTRA_FULL_SCREEN, false)
            }
            ACTION_RING -> Intent(context, AlarmRingService::class.java).apply {
                action = AlarmRingService.ACTION_RING
                putExtra(AlarmRingService.EXTRA_VOLUME, 1.0f)
                putExtra(AlarmRingService.EXTRA_CHANNEL, SleepNotificationHelper.CH_WAKE_FULL)
                putExtra(AlarmRingService.EXTRA_TITLE, "Wake up!")
                putExtra(AlarmRingService.EXTRA_FULL_SCREEN, true)
            }
            else -> return
        }
        context.startForegroundService(serviceIntent)
    }

    companion object {
        const val ACTION_GENTLE = "com.waypoint.app.WAKE_GENTLE"
        const val ACTION_MEDIUM = "com.waypoint.app.WAKE_MEDIUM"
        const val ACTION_RING   = "com.waypoint.app.WAKE_RING"
    }
}
