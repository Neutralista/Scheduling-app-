package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScriptNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("notif_id") ?: return
        val config = NotificationStore.load(context, id) ?: return
        NotificationHelper.sendScriptNotification(context, config)
        if (config.weekly != null) {
            ScriptNotificationScheduler.rescheduleWeekly(context, id)
        }
    }
}
