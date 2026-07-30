package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_GENTLE -> SleepNotificationHelper.sendWakeGentleNotification(context)
            ACTION_MEDIUM -> SleepNotificationHelper.sendWakeMediumNotification(context)
        }
    }

    companion object {
        const val ACTION_GENTLE = "com.waypoint.app.WAKE_GENTLE"
        const val ACTION_MEDIUM = "com.waypoint.app.WAKE_MEDIUM"
    }
}
